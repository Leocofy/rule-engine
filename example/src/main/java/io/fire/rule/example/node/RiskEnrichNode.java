package io.fire.rule.example.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.config.NodeComponent;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.NodeException;
import io.fire.rule.spi.RuleNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 多因子风险评估节点：把原始遥测"加工"成可决策的风险画像。
 *
 * <p>评分模型（0~100）由四部分组成：
 * <ul>
 *   <li>温度、烟雾、CO 相对各自阈值的超标程度</li>
 *   <li>最近窗口内的升温趋势</li>
 *   <li>设备电量偏低带来的可信度惩罚</li>
 * </ul>
 *
 * <p>输出在原 payload 上追加 {@code risk} 与 {@code alert} 两段：
 * 保留 JSON 结构（不像 transform 节点那样打平），下游仍可按字段路径取值；
 * 同时把 riskScore / riskLevel / alertId 写入 metadata，方便日志与通知复用。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@NodeComponent(type = "riskEnrich", category = "analysis",
        description = "多因子风险评估：计算风险分、等级、因子与处置建议")
public class RiskEnrichNode implements RuleNode {

    private static final Logger LOG = LoggerFactory.getLogger(RiskEnrichNode.class);
    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final ObjectMapper objectMapper;

    public RiskEnrichNode(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        double criticalScore = toDouble(ctx.nodeConfig().getString("criticalScore"), 80d);
        double warnScore = toDouble(ctx.nodeConfig().getString("warnScore"), 50d);

        Map<String, Object> root;
        try {
            root = objectMapper.readValue(msg.getPayload(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            ctx.tellFailure(msg, new NodeException("riskEnrich 无法解析 payload", true));
            return;
        }

        Map<String, Object> device = asMap(root.get("device"));
        Map<String, Object> telemetry = asMap(root.get("telemetry"));
        Map<String, Object> threshold = asMap(root.get("threshold"));
        List<Object> window = asList(root.get("recentWindow"));

        double temperature = number(telemetry.get("temperature"), 0d);
        double smokeDensity = number(telemetry.get("smokeDensity"), 0d);
        double coLevel = number(telemetry.get("coLevel"), 0d);
        double tempThreshold = number(threshold.get("temperature"), 80d);
        double smokeThreshold = number(threshold.get("smokeDensity"), 0.5d);
        double coThreshold = number(threshold.get("coLevel"), 24d);

        double tempRatio = ratio(temperature, tempThreshold);
        double smokeRatio = ratio(smokeDensity, smokeThreshold);
        double coRatio = ratio(coLevel, coThreshold);

        double tempScore = clamp((tempRatio - 0.70) / 0.40, 0d, 1d) * 45d;
        double smokeScore = clamp((smokeRatio - 0.50) / 0.60, 0d, 1d) * 35d;
        double coScore = clamp((coRatio - 0.50) / 1.00, 0d, 1d) * 10d;
        Trend trend = evaluateTrend(window, temperature);
        double battery = number(asMap(device.get("status")).get("battery"), 100d);
        double batteryScore = battery < 20 ? 5d : 0d;

        double score = Math.min(100d, tempScore + smokeScore + coScore + trend.score + batteryScore);
        String level = level(score, criticalScore, warnScore);

        List<String> factors = new ArrayList<>();
        if (tempRatio > 1d) {
            factors.add("温度超阈值 " + percent(tempRatio - 1d));
        }
        if (smokeRatio > 1d) {
            factors.add("烟雾浓度超阈值 " + percent(smokeRatio - 1d));
        }
        if (coRatio > 1d) {
            factors.add("CO 浓度超阈值 " + percent(coRatio - 1d));
        }
        if (trend.rising) {
            factors.add("3 分钟窗口内升温 " + percent(trend.deltaRatio));
        }
        if (battery < 20) {
            factors.add("设备电量仅 " + (int) battery + "%，数据可信度下降");
        }
        if (factors.isEmpty()) {
            factors.add("各项指标均在阈值内");
        }

        String deviceId = asText(device.get("deviceId"), msg.getOriginator());
        String zone = asText(asMap(device.get("location")).get("zone"), "未知区域");
        String alertId = "alert-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("score", round2(score));
        risk.put("level", level);
        risk.put("factors", factors);
        risk.put("windowTrend", trend.rising ? "RISING" : trend.falling ? "FALLING" : "STABLE");
        risk.put("evaluatedAt", OffsetDateTime.now(ZONE).format(ISO_MILLIS));

        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("alertId", alertId);
        alert.put("deviceId", deviceId);
        alert.put("zone", zone);
        alert.put("level", level);
        alert.put("channels", channels(level));
        alert.put("suggestedAction", suggestedAction(level));
        alert.put("dedupKey", deviceId + ":" + level);

        root.put("risk", risk);
        root.put("alert", alert);

        msg.getMetaData().put("riskScore", String.format("%.2f", score));
        msg.getMetaData().put("riskLevel", level);
        msg.getMetaData().put("alertId", alertId);

        LOG.info("风险评估完成: device={} score={} level={} factors={}",
                deviceId, String.format("%.2f", score), level, factors);
        ctx.tellSuccess(msg.transform()
                .payload(objectMapper.writeValueAsString(root))
                .build());
    }

    private Trend evaluateTrend(List<Object> window, double current) {
        Trend trend = new Trend();
        if (window == null || window.size() < 2) {
            return trend;
        }
        double first = number(asMap(window.get(0)).get("temperature"), current);
        double last = number(asMap(window.get(window.size() - 1)).get("temperature"), current);
        if (first <= 0d) {
            return trend;
        }
        trend.deltaRatio = (last - first) / first;
        if (trend.deltaRatio >= 0.10d) {
            trend.rising = true;
            trend.score = Math.min(10d, trend.deltaRatio * 40d);
        } else if (trend.deltaRatio <= -0.10d) {
            trend.falling = true;
        }
        return trend;
    }

    private String level(double score, double criticalScore, double warnScore) {
        if (score >= criticalScore) {
            return "CRITICAL";
        }
        if (score >= warnScore) {
            return "HIGH";
        }
        return score >= 30d ? "MEDIUM" : "LOW";
    }

    private List<String> channels(String level) {
        switch (level) {
            case "CRITICAL":
                return Arrays.asList("DINGTALK", "SMS", "SIREN");
            case "HIGH":
                return Arrays.asList("DINGTALK", "SMS");
            case "MEDIUM":
                return Arrays.asList("DINGTALK");
            default:
                return new ArrayList<>();
        }
    }

    private String suggestedAction(String level) {
        switch (level) {
            case "CRITICAL":
                return "EVACUATE_AND_DISPATCH";
            case "HIGH":
                return "DISPATCH_INSPECTION";
            case "MEDIUM":
                return "MONITOR";
            default:
                return "ARCHIVE";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : null;
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

    private double ratio(double value, double threshold) {
        return threshold <= 0d ? 0d : value / threshold;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String percent(double ratio) {
        return Math.round(ratio * 100d) + "%";
    }

    private double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private double toDouble(String value, double defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 窗口趋势判定结果。
     */
    private static final class Trend {
        private boolean rising;
        private boolean falling;
        private double deltaRatio;
        private double score;
    }
}
