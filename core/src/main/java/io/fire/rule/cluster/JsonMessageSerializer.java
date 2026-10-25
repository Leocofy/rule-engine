package io.fire.rule.cluster;

import io.fire.rule.model.MetaData;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.util.JsonUtil;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * JSON 序列化实现（零外部依赖）。
 *
 * <p>生产环境建议换 Protobuf / Kryo：
 * <ul>
 *   <li>JSON 体积大，百万级消息下带宽与存储成本明显</li>
 *   <li>Protobuf 与 ThingsBoard 兼容，便于对接现有生态</li>
 * </ul>
 * 但 JSON 胜在可读、可调、零依赖，作为内置默认实现合适。
 */
public class JsonMessageSerializer implements MessageSerializer {

    private static final Charset UTF8 = StandardCharsets.UTF_8;

    @Override
    public byte[] serialize(RuleMessage msg, String chainId, String nodeId, Set<String> relations) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", msg.getId());
        root.put("type", msg.getType());
        root.put("originator", msg.getOriginator());
        root.put("payload", msg.getPayload());
        root.put("chainId", chainId);
        root.put("nodeId", nodeId);

        Map<String, String> meta = msg.getMetaData().values();
        if (!meta.isEmpty()) {
            root.put("meta", new LinkedHashMap<String, Object>(meta));
        }
        if (relations != null && !relations.isEmpty()) {
            List<Object> rels = new ArrayList<Object>();
            for (String r : relations) {
                rels.add(r);
            }
            root.put("relations", rels);
        }
        return toJson(root).getBytes(UTF8);
    }

    @Override
    public TransportMessage deserialize(byte[] data) {
        Map<String, Object> root = JsonUtil.parseObject(data);

        TransportMessage t = new TransportMessage();
        t.setId(str(root.get("id")));
        t.setType(str(root.get("type")));
        t.setOriginator(str(root.get("originator")));
        t.setPayload(str(root.get("payload")));
        t.setChainId(str(root.get("chainId")));
        t.setNodeId(str(root.get("nodeId")));

        Object meta = root.get("meta");
        if (meta != null) {
            Map<String, Object> m = JsonUtil.asObject(meta);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                sb.append(e.getKey()).append('\u0001').append(JsonUtil.asString(e.getValue())).append('\u0002');
            }
            t.setMetaData(sb.toString().getBytes(UTF8));
        }
        Set<String> rels = new LinkedHashSet<>();
        for (Object r : JsonUtil.asArray(root.get("relations"))) {
            rels.add(JsonUtil.asString(r));
        }
        t.setRelations(rels);
        return t;
    }

    /**
     * 把 TransportMessage 还原成引擎内部消息。
     */
    @Override
    public RuleMessage toRuleMessage(TransportMessage t) {
        RuleMessage.Builder b = RuleMessage.newMessage()
                .id(t.getId())
                .type(t.getType())
                .originator(t.getOriginator())
                .payload(t.getPayload());
        MetaData md = decodeMetaData(t.getMetaData());
        if (md != null) {
            b.metaData(md);
        }
        return b.build();
    }

    private MetaData decodeMetaData(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        MetaData md = new MetaData();
        String s = new String(raw, UTF8);
        for (String pair : s.split("\u0002")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] kv = pair.split("\u0001");
            if (kv.length == 2) {
                md.put(kv[0], kv[1]);
            }
        }
        return md;
    }

    private String str(Object o) {
        return o == null ? null : JsonUtil.asString(o);
    }

    /**
     * 简易 JSON 序列化（只覆盖本类用到的类型）。
     */
    private String toJson(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
                sb.append(toJson(e.getValue()));
                first = false;
            }
            return sb.append('}').toString();
        }
        if (o instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<?>) o) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(toJson(item));
                first = false;
            }
            return sb.append(']').toString();
        }
        if (o instanceof Number || o instanceof Boolean) {
            return String.valueOf(o);
        }
        return '"' + escape(String.valueOf(o)) + '"';
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
