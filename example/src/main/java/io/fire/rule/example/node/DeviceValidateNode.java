package io.fire.rule.example.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.config.NodeComponent;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.NodeConfig;
import io.fire.rule.spi.NodeException;
import io.fire.rule.spi.RuleNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 设备遥测校验节点：把"脏数据"挡在业务链路之外。
 *
 * <p>校验项：
 * <ul>
 *   <li>payload 必须是 JSON 对象</li>
 *   <li>device.deviceId / device.status.online / telemetry 关键指标必须存在</li>
 * </ul>
 *
 * <p>校验结论通过关系名表达：{@code Valid} 进入主链路，{@code Invalid} 进入归档通道，
 * 失败原因写入 metadata（invalidReason），供下游归档节点记录。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@NodeComponent(type = "deviceValidate", category = "validate",
        description = "校验设备遥测报文完整性与设备在线状态",
        relations = {"Valid", "Invalid"})
public class DeviceValidateNode implements RuleNode {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceValidateNode.class);

    private final ObjectMapper objectMapper;

    public DeviceValidateNode(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {
        // 无配置项：校验规则固化在节点内，真实项目可改为按 deviceType 加载校验模板
    }

    @Override
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        Map<String, Object> root = readPayload(msg.getPayload());
        if (root == null) {
            invalid(ctx, msg, "payload 不是合法的 JSON 对象");
            return;
        }

        Map<String, Object> device = asMap(root.get("device"));
        if (device == null || isBlank(device.get("deviceId"))) {
            invalid(ctx, msg, "缺少 device.deviceId");
            return;
        }
        Map<String, Object> telemetry = asMap(root.get("telemetry"));
        if (telemetry == null) {
            invalid(ctx, msg, "缺少 telemetry 遥测数据");
            return;
        }
        if (!isNumber(telemetry.get("temperature"))) {
            invalid(ctx, msg, "缺少 telemetry.temperature");
            return;
        }
        if (!isNumber(telemetry.get("smokeDensity"))) {
            invalid(ctx, msg, "缺少 telemetry.smokeDensity");
            return;
        }
        if (isOffline(device)) {
            invalid(ctx, msg, "设备离线，遥测数据不可信");
            return;
        }

        Map<String, Object> location = asMap(device.get("location"));
        msg.getMetaData().put("deviceId", String.valueOf(device.get("deviceId")));
        msg.getMetaData().put("deviceType", asText(device.get("deviceType")));
        if (location != null) {
            msg.getMetaData().put("site", asText(location.get("site")));
            msg.getMetaData().put("zone", asText(location.get("zone")));
        }
        LOG.debug("遥测校验通过: device={}", device.get("deviceId"));
        ctx.tellNext(msg, "Valid");
    }

    private void invalid(INodeContext ctx, RuleMessage msg, String reason) {
        msg.getMetaData().put("invalidReason", reason);
        LOG.warn("遥测校验不通过: originator={} reason={}", msg.getOriginator(), reason);
        ctx.tellNext(msg, "Invalid");
    }

    private boolean isOffline(Map<String, Object> device) {
        Map<String, Object> status = asMap(device.get("status"));
        if (status == null) {
            return true;
        }
        Object online = status.get("online");
        return !(Boolean.TRUE.equals(online) || "true".equalsIgnoreCase(asText(online)));
    }

    private Map<String, Object> readPayload(String payload) {
        if (payload == null || !payload.trim().startsWith("{")) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private boolean isNumber(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value instanceof String) {
            try {
                Double.parseDouble(((String) value).trim());
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean isBlank(Object value) {
        return value == null || value.toString().trim().isEmpty();
    }

    private String asText(Object value) {
        return value == null ? null : value.toString();
    }
}
