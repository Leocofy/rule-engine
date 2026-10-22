package io.fire.rule.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fire.rule.RuleEngine;
import io.fire.rule.example.source.CallBackSource;
import io.fire.rule.model.MetaData;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.runtime.Envelope;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Example 启动类：演示如何使用 rule-engine-spring-boot-starter。
 *
 * <p>starter 的自动配置会自动装配 {@link io.fire.rule.RuleEngine}，
 * 业务侧只需提供节点服务 Bean 和规则链 JSON 即可。
 *
 * <p>HTTP 入口接收真实的嵌套设备报文（设备档案 / 多指标遥测 / 阈值 / 最近窗口），
 * 缺失的事件头由服务端补全；{@code device.deviceId} 作为消息 originator，
 * 保证同一设备的消息串行处理。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@RestController
@SpringBootApplication
public class ExampleApplication {

    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    @Resource
    private CallBackSource callBackSource;

    @Resource
    private ObjectMapper objectMapper;

    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }

    @PostMapping("/ruleExecute")
    public Map<String, Object> ruleExecute(@RequestBody JsonNode body,
                                           @RequestHeader(value = "X-Source", required = false) String source)
            throws Exception {
        ObjectNode root;
        if (body != null && body.isObject()) {
            root = (ObjectNode) body;
        } else {
            root = objectMapper.createObjectNode();
            if (body != null && !body.isNull()) {
                root.set("payload", body);
            }
        }

        OffsetDateTime now = OffsetDateTime.now(ZONE);
        String traceId = text(root.path("traceId"));
        if (traceId.isEmpty()) {
            traceId = "trace-" + now.toInstant().toEpochMilli() + "-" + shortId();
            root.put("traceId", traceId);
        }
        if (!root.path("event").isObject()) {
            ObjectNode event = root.putObject("event");
            event.put("eventId", "evt-" + shortId());
            event.put("type", "DEVICE_TELEMETRY");
            event.put("source", source == null || source.isEmpty() ? "HTTP_CALLBACK" : source);
            event.put("reportedAt", now.format(ISO_MILLIS));
            event.put("reportedBy", "manual-api");
            event.put("priority", "MANUAL");
        }

        String deviceId = text(root.path("device").path("deviceId"));
        if (deviceId.isEmpty()) {
            deviceId = "http-unknown";
        }
        String type = text(root.path("event").path("type"));
        if (type.isEmpty()) {
            type = "DEVICE_TELEMETRY";
        }

        MetaData metadata = new MetaData();
        metadata.put("traceId", traceId);
        metadata.put("ingestSource", "HTTP_CALLBACK");

        callBackSource.add(new Envelope(RuleMessage.newMessage()
                .type(type)
                .originator(deviceId)
                .payload(objectMapper.writeValueAsString(root))
                .metaData(metadata)
                .build()));

        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("accepted", true);
        accepted.put("traceId", traceId);
        accepted.put("deviceId", deviceId);
        accepted.put("chainId", "fireAlarm");
        return accepted;
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
