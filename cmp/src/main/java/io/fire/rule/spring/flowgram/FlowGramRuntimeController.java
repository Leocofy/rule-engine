package io.fire.rule.spring.flowgram;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.spring.flowgram.runtime.FlowGramRuntimeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FlowGram 运行时（Runtime）后端接口，实现官方 Runtime API 契约，供
 * {@code @flowgram.ai/runtime-js} 的 server 模式（{@code createRuntimePlugin({mode:'server'})}）直接对接。
 *
 * <p>客户端固定以 {@code {protocol}://{domain}:{port}/api + path} 拼 URL，故本控制器统一挂在 {@code /api}：
 * <ul>
 *   <li>{@code POST /api/task/validate} —— 校验 schema</li>
 *   <li>{@code POST /api/task/run} —— 启动任务，返回 taskID</li>
 *   <li>{@code GET  /api/task/report?taskID=} —— 轮询执行报告</li>
 *   <li>{@code GET  /api/task/report/stream?taskID=} —— SSE 报告流（逐节点实时推送，替代轮询）</li>
 *   <li>{@code GET  /api/task/result?taskID=} —— 取最终输出</li>
 *   <li>{@code PUT  /api/task/cancel} —— 取消任务</li>
 *   <li>{@code GET  /api/info} —— 运行时信息</li>
 * </ul>
 *
 * <p>响应约定：成功体<b>不含</b>顶层 {@code code} 字段（客户端以 {@code code} 的存在判定错误）。
 *
 * @author Cai FeiYue
 * @date 2026-11-04
 */
@RestController
@RequestMapping("/api")
public class FlowGramRuntimeController {

    private final FlowGramRuntimeService runtimeService;
    private final ObjectMapper objectMapper;

    public FlowGramRuntimeController(FlowGramRuntimeService runtimeService, ObjectMapper objectMapper) {
        this.runtimeService = runtimeService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/task/validate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> validate(@RequestBody JsonNode body) {
        return runtimeService.validate(workflowId(body), schema(body));
    }

    @PostMapping(value = "/task/run", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> run(@RequestBody JsonNode body) {
        return runtimeService.run(workflowId(body), schema(body), inputs(body));
    }

    @GetMapping(value = "/task/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> report(@RequestParam("taskID") String taskID) {
        return runtimeService.report(taskID);
    }

    /**
     * 报告流（SSE）：逐节点实时推送 report 快照，替代 {@code /task/report} 轮询。
     * 事件名 {@code report} 携带完整报告体，{@code end} 表示任务终止、流即将关闭。
     */
    @GetMapping(value = "/task/report/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reportStream(@RequestParam("taskID") String taskID) {
        return runtimeService.subscribeReport(taskID);
    }

    @GetMapping(value = "/task/result", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> result(@RequestParam("taskID") String taskID) {
        return runtimeService.result(taskID);
    }

    @PutMapping(value = "/task/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> cancel(@RequestBody JsonNode body) {
        String taskID = body == null ? null : body.path("taskID").asText(null);
        return runtimeService.cancel(taskID);
    }

    @GetMapping(value = "/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "rule-engine");
        info.put("runtime", "java");
        info.put("version", "1.0.0");
        info.put("time", Instant.now().toString());
        return info;
    }

    private String workflowId(JsonNode body) {
        if (body == null) {
            return null;
        }
        String id = body.path("workflowId").asText(null);
        if (id == null || id.isEmpty()) {
            id = body.path("id").asText(null);
        }
        return id;
    }

    private String schema(JsonNode body) {
        if (body == null) {
            return null;
        }
        JsonNode schemaNode = body.get("schema");
        if (schemaNode == null || schemaNode.isNull()) {
            return null;
        }
        // schema 约定为 JSON 字符串；容错处理：若直接给了对象则序列化回字符串
        return schemaNode.isTextual() ? schemaNode.asText() : schemaNode.toString();
    }

    private Map<String, Object> inputs(JsonNode body) {
        if (body == null) {
            return new LinkedHashMap<>();
        }
        JsonNode inputsNode = body.get("inputs");
        if (inputsNode == null || !inputsNode.isObject()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(inputsNode, new TypeReference<>() {
        });
    }
}
