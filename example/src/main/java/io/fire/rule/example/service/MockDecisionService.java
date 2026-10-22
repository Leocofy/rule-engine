package io.fire.rule.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.example.node.AiDecisionNode;
import io.fire.rule.model.RuleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 示例 AI 判定服务：读取风险评估节点产出的风险画像，给出处置结论。
 *
 * <p>结论直接作为关系名（立即处置 / 持续观察 / 无需处置），
 * 同时写入 metadata（decision / decisionAgent），供落库与通知节点复用。
 *
 * <p>自动被引擎 capability 机制注入到 {@link AiDecisionNode} 节点中。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@Component
public class MockDecisionService implements AiDecisionNode.DecisionService {

    private static final Logger LOG = LoggerFactory.getLogger(MockDecisionService.class);

    private final ObjectMapper objectMapper;

    public MockDecisionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String decide(String agent, RuleMessage msg) {
        Map<String, Object> root = readPayload(msg.getPayload());
        Map<String, Object> risk = asMap(root.get("risk"));
        Map<String, Object> device = asMap(root.get("device"));
        Map<String, Object> telemetry = asMap(root.get("telemetry"));

        double score = number(risk.get("score"), 0d);
        String level = asText(risk.get("level"), "LOW");
        List<?> factors = asList(risk.get("factors"));
        String deviceId = asText(device.get("deviceId"), msg.getOriginator());

        String decision;
        if ("CRITICAL".equals(level) || score >= 80d) {
            decision = "立即处置";
        } else if ("HIGH".equals(level) || score >= 50d) {
            decision = "持续观察";
        } else {
            decision = "无需处置";
        }

        msg.getMetaData().put("decision", decision);
        msg.getMetaData().put("decisionAgent", agent);
        LOG.info("AI 判定 [agent={}] device={} temp={} smoke={} score={} level={} factors={} => {}",
                agent, deviceId, telemetry.get("temperature"), telemetry.get("smokeDensity"),
                String.format("%.2f", score), level, factors, decision);
        return decision;
    }

    private Map<String, Object> readPayload(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            LOG.warn("payload 解析失败，按无风险处理: {}", payload);
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private List<?> asList(Object value) {
        return value instanceof List ? (List<?>) value : Collections.emptyList();
    }

    private double number(Object value, double defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String asText(Object value, String defaultValue) {
        return value == null || value.toString().trim().isEmpty() ? defaultValue : value.toString();
    }
}
