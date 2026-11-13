package io.fire.rule.example.node;

import io.fire.rule.example.config.NodeCategory;
import io.fire.rule.spring.annotation.NodeComponent;
import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.core.spi.RuleNode;
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
//@NodeComponent(type = "deviceValidate", category = NodeCategory.BUSINESS,
//        description = "校验设备遥测报文完整性与设备在线状态")
public class DeviceValidateNode implements RuleNode {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceValidateNode.class);

    @Override
    public void onMsg(INodeContext ctx) throws Exception {
        Map<String, Object> root = ctx.state().entry();
        if (root == null || root.isEmpty()) {
            invalid(ctx, "payload 不是合法的 JSON 对象");
            return;
        }

        Map<String, Object> device = asMap(root.get("device"));
        if (device == null || isBlank(device.get("deviceId"))) {
            invalid(ctx, "缺少 device.deviceId");
            return;
        }
        Map<String, Object> telemetry = asMap(root.get("telemetry"));
        if (telemetry == null) {
            invalid(ctx, "缺少 telemetry 遥测数据");
            return;
        }
        if (!isNumber(telemetry.get("temperature"))) {
            invalid(ctx, "缺少 telemetry.temperature");
            return;
        }
        if (!isNumber(telemetry.get("smokeDensity"))) {
            invalid(ctx, "缺少 telemetry.smokeDensity");
            return;
        }
        if (isOffline(device)) {
            invalid(ctx, "设备离线，遥测数据不可信");
            return;
        }

        Map<String, Object> location = asMap(device.get("location"));
        ctx.metaPut("deviceId", String.valueOf(device.get("deviceId")));
        ctx.metaPut("deviceType", asText(device.get("deviceType")));
        if (location != null) {
            ctx.metaPut("site", asText(location.get("site")));
            ctx.metaPut("zone", asText(location.get("zone")));
        }
        LOG.debug("遥测校验通过: device={}", device.get("deviceId"));
        ctx.next("Valid");
    }

    private void invalid(INodeContext ctx, String reason) {
        ctx.metaPut("invalidReason", reason);
        LOG.warn("遥测校验不通过: node={} reason={}", ctx.getNodeId(), reason);
        ctx.next("Invalid");
    }

    private boolean isOffline(Map<String, Object> device) {
        Map<String, Object> status = asMap(device.get("status"));
        if (status == null) {
            return true;
        }
        Object online = status.get("online");
        return !(Boolean.TRUE.equals(online) || "true".equalsIgnoreCase(asText(online)));
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
