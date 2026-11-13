package io.fire.rule.example.node;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.core.spi.RuleNode;
import io.fire.rule.core.util.JsonUtil;
import io.fire.rule.example.config.NodeCategory;
import io.fire.rule.spring.annotation.NodeComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 拉取节点：把"PULL HTTP SOURCE"下沉为可编排的组件。
 *
 * <p>与 {@code PullSource}（引擎外部的消息源）不同，本节点在链内运行——收到消息后按配置
 * 主动向外部接口发起 HTTP 请求，把响应体作为产出交给下游，从而实现"消息驱动的按需拉取"
 * 与"链式补数据"（如按设备 id 回查档案、按告警拉取工单）。
 *
 * <p>耗时 IO 一律走 {@link INodeContext#asyncExecutor()}，不阻塞引擎线程；
 * 2xx/3xx 走 Success，其余状态码或异常走 Failure。URL、请求头、请求体的字段值可用
 * {@code IFlowValue}（constant/ref/expression/template）声明，运行期由
 */
@NodeComponent(type = "httpPull", name = "HTTP拉取", category = NodeCategory.IO)
public class HttpPullNode implements RuleNode {

    private static final Logger LOG = LoggerFactory.getLogger(HttpPullNode.class);

    /**
     * HttpClient 线程安全，节点单例复用一份即可，连接超时固定 10s。
     */
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public void onMsg(final INodeContext ctx) throws Exception {
        // bindInputs：ValueResolver 先把 inputsValues（constant/ref/expression/template）解析成真实值，再绑定强类型 Config
        final Config config = ctx.config(Config.class);

        final String url = config.url;
        if (url == null || url.trim().isEmpty()) {
            ctx.fail(new IllegalArgumentException("httpPull 节点缺少必填 url"));
            return;
        }
        final String method = config.method != null ? config.method.name() : "GET";
        final Map<String, String> headers = config.headers;
        final String body = config.body != null && !config.body.isEmpty() ? JsonUtil.toJsonString(config.body) : "";
        final long timeoutMs = config.timeoutMs;
        final boolean keepPayload = config.keepPayload;
        final String resultMetaKey = config.resultMetaKey;

        ctx.asyncExecutor().execute(() -> {
            if (!ctx.isValid()) {
                return;
            }
            try {
                HttpRequest request = buildRequest(url, method, headers, body, timeoutMs);
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                String responseBody = response.body() == null ? "" : response.body();
                LOG.debug("httpPull {} {} -> {}", method, url, status);

                if (status < 200 || status >= 400) {
                    ctx.fail(new IllegalStateException("HTTP " + status + "：" + brief(responseBody)));
                    return;
                }
                produce(ctx, responseBody, status, keepPayload, resultMetaKey);
                ctx.next();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ctx.fail(e);
            } catch (Exception e) {
                ctx.fail(e);
            }
        });
    }

    private HttpRequest buildRequest(String url, String method, Map<String, String> headers, String body, long timeoutMs) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url));
        if (timeoutMs > 0) {
            builder.timeout(Duration.ofMillis(timeoutMs));
        }
        applyHeaders(builder, headers);

        HttpRequest.BodyPublisher publisher = ("POST".equals(method) || "PUT".equals(method))
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();
        return builder.method(method, publisher).build();
    }

    private void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
    }

    private void produce(INodeContext ctx, String body, int status,
                         boolean keepPayload, String resultMetaKey) {
        // 构造 Output 对象（声明了产出字段），写入命名空间供下游 ref 取值
        Output output = new Output();
        output.response = body;
        ctx.emit(JsonUtil.asObject(JsonUtil.toJsonString(output)));
        if (keepPayload) {
            // 保留原负载：响应写入指定 metadata 键
            ctx.metaPut(resultMetaKey, body);
        } else {
            // 用响应体替换入口负载（working data）
            ctx.replaceEntry(parseEntry(body));
        }
    }

    /** 把响应体解析成入口映射：合法 JSON 对象原样，否则包装 {@code {_raw: ...}}。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseEntry(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = JsonUtil.parseAny(payload);
            if (parsed instanceof Map) {
                return new LinkedHashMap<>((Map<String, Object>) parsed);
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("_raw", parsed);
            return wrapper;
        } catch (Exception e) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("_raw", payload);
            return wrapper;
        }
    }

    private String brief(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    /**
     * HTTP 拉取节点配置：字段名与运行期 {@code ctx.nodeConfig()} 读取的键一一对应。
     */
    public static class Config {

        @NotBlank
        @Pattern(regexp = "^https?://.+$", message = "需为 http(s) 地址")
        @JsonPropertyDescription("请求地址：可写字面量，也可用 {ref:[nodeId, path]} 引用上游 String 产出；引用值运行期解析后再校验")
        public String url;

        @NotNull
        @JsonPropertyDescription("请求方法")
        public Method method = Method.GET;

        @JsonPropertyDescription("键值对形式的请求头；每个值是字符串，可写字面量或用 {ref:[nodeId, path]} 引用上游 String 产出")
        public Map<String, String> headers = new HashMap<>();

        @JsonPropertyDescription("POST/PUT 的请求体（可自定义嵌套结构）；任意叶子可用 {ref:[nodeId, path]} 引用上游产出；仅在方法为 POST/PUT 时需要")
        public Map<String, Object> body = new HashMap<>();

        @Min(0)
        @Max(60000)
        @JsonPropertyDescription("单次请求超时，0 表示不限制")
        public int timeoutMs = 5000;

        @JsonPropertyDescription("true 时原 payload 不变、响应写入 metadata；false 时用响应体替换 payload")
        public boolean keepPayload;

        @JsonPropertyDescription("仅在\"保留原负载\"为 true 时生效，响应体写入该 metadata 键")
        public String resultMetaKey = "http.response";
    }

    public enum Method {
        GET, POST, PUT, DELETE
    }

    /**
     * 输出声明（"输出即 Schema"）：本节点交给下游的产出字段与类型，供下游 ref 选择/对齐。
     * 运行期把响应体写入该结构（M3 产出命名空间）。
     */
    public static class Output {

        @JsonPropertyDescription("HTTP 响应体（按 JSON 解析后的原始内容）")
        public String response;
    }

}
