package io.fire.rule.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.example.node.AsyncSinkNode;
import io.fire.rule.model.RuleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 示例外部写入处理器：按 target 模拟落库 / 通知 / 归档三类副作用。
 *
 * <p>自动被引擎 capability 机制注入到 sink 类节点中。
 * 内部保留最近投递记录，便于本地调试与集成测试断言（生产环境不应这样写）。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@Component
public class MockSinkHandler implements AsyncSinkNode.SinkHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MockSinkHandler.class);
    private static final int MAX_RECORDS = 200;

    private final ObjectMapper objectMapper;
    private final Map<String, Deque<String>> records = new ConcurrentHashMap<>();

    public MockSinkHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(String target, RuleMessage msg) {
        Map<String, Object> root = readPayload(msg.getPayload());
        Map<String, Object> device = asMap(root.get("device"));
        Map<String, Object> risk = asMap(root.get("risk"));
        Map<String, Object> alert = asMap(root.get("alert"));

        String deviceId = asText(device.get("deviceId"), msg.getOriginator());
        String level = asText(risk.get("level"), "UNKNOWN");
        String score = risk.get("score") == null ? "-" : String.valueOf(risk.get("score"));
        String alertId = asText(alert.get("alertId"), "-");

        record(target, deviceId + "|" + level + "|" + score + "|" + alertId + "|" + target);

        switch (target) {
            case "tdengine":
                LOG.info("[TDengine] 写入时序库: device={} level={} score={} alertId={} decision={}",
                        deviceId, level, score, alertId, msg.getMetaData().get("decision"));
                break;
            case "notify":
                LOG.info("[通知] 渠道={} device={} zone={} level={} alertId={} 建议动作={}",
                        alert.get("channels"), deviceId, alert.get("zone"), level, alertId,
                        alert.get("suggestedAction"));
                break;
            case "archive":
                LOG.info("[归档] device={} level={} score={} reason={}",
                        deviceId, level, score, msg.getMetaData().get("invalidReason"));
                break;
            default:
                LOG.info("[{}] 投递成功: device={} level={} alertId={}", target, deviceId, level, alertId);
        }
    }

    /**
     * 查询某个目的端最近收到的投递记录，格式：deviceId|level|score|alertId|target。
     */
    public List<String> delivered(String target) {
        Deque<String> queue = records.get(target);
        return queue == null ? Collections.emptyList() : new ArrayList<>(queue);
    }

    public void reset() {
        records.clear();
    }

    private void record(String target, String record) {
        Deque<String> queue = records.computeIfAbsent(target, k -> new ConcurrentLinkedDeque<>());
        queue.addFirst(record);
        while (queue.size() > MAX_RECORDS) {
            queue.pollLast();
        }
    }

    private Map<String, Object> readPayload(String payload) {
        if (payload == null || !payload.trim().startsWith("{")) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private String asText(Object value, String defaultValue) {
        return value == null || value.toString().trim().isEmpty() ? defaultValue : value.toString();
    }
}
