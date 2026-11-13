package io.fire.rule.core.cluster;

import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.util.JsonUtil;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 内置 JSON 序列化器：把信封 + {@link ExecutionState} 快照编成字节流，跨实例随行投递。
 *
 * <p>统一 State 模型下，负载 / 命名空间产出 / 元数据 / 作用域都收敛进 {@code state} 一个 JSON 段，
 * 不再单独编码 metadata；{@code state} 以原生 JSON 对象内嵌（避免二次转义），整体可 {@code deserialize → resume} 无损还原。
 */
public class JsonMessageSerializer implements MessageSerializer {

    private static final Charset UTF8 = StandardCharsets.UTF_8;

    @Override
    public byte[] serialize(RuleMessage msg, String chainId, String nodeId, Set<String> relations) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendStr(sb, "id", msg.getId());
        sb.append(',');
        appendStr(sb, "type", msg.getType());
        sb.append(',');
        appendStr(sb, "originator", msg.getOriginator());
        sb.append(',');
        appendStr(sb, "chainId", chainId);
        sb.append(',');
        appendStr(sb, "nodeId", nodeId);
        sb.append(",\"state\":");
        sb.append(msg.getState() == null ? ExecutionState.EMPTY.toJson() : msg.getState().toJson());
        if (relations != null && !relations.isEmpty()) {
            sb.append(",\"relations\":[");
            boolean first = true;
            for (String r : relations) {
                if (!first) {
                    sb.append(',');
                }
                sb.append('"').append(escape(r)).append('"');
                first = false;
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString().getBytes(UTF8);
    }

    @Override
    public TransportMessage deserialize(byte[] data) {
        Map<String, Object> root = JsonUtil.parseObject(data);

        TransportMessage t = new TransportMessage();
        t.setId(JsonUtil.asString(root.get("id")));
        t.setType(JsonUtil.asString(root.get("type")));
        t.setOriginator(JsonUtil.asString(root.get("originator")));
        t.setChainId(JsonUtil.asString(root.get("chainId")));
        t.setNodeId(JsonUtil.asString(root.get("nodeId")));
        Object state = root.get("state");
        t.setState(state == null ? null : JsonUtil.toJsonString(state));
        Set<String> rels = new LinkedHashSet<>();
        for (Object r : JsonUtil.asArray(root.get("relations"))) {
            rels.add(JsonUtil.asString(r));
        }
        t.setRelations(rels);
        return t;
    }

    /**
     * 把 TransportMessage 还原成引擎内部信封（携带状态快照）。
     */
    @Override
    public RuleMessage toRuleMessage(TransportMessage t) {
        return RuleMessage.newMessage()
                .id(t.getId())
                .type(t.getType())
                .originator(t.getOriginator())
                .state(ExecutionState.fromJson(t.getState()))
                .build();
    }

    private static void appendStr(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        sb.append(value == null ? "null" : '"' + escape(value) + '"');
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
