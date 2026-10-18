package io.fire.rule.nodes;

import io.fire.rule.util.JsonUtil;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.IExpressionEngine;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 表达式引擎的极简内置实现（占位）。
 *
 * <p><b>生产环境请替换为 Jsonata / Aviator / QLExpress 实现。</b>
 * 你已有的 Jsonata 字段映射引擎只需实现 {@link IExpressionEngine} 三个方法即可接入，
 * 引擎其他部分无需改动。
 *
 * <p>本实现支持的语法（够跑通演示与单元测试）：
 * <ul>
 *   <li>evaluate: {@code temp > 80}、{@code level == critical}、{@code status != ok}</li>
 *   <li>transform: {@code set:level=critical} 写 metadata；{@code put:tempF=175} 写 payload</li>
 *   <li>route: 取 metadata 中指定键的值作为关系名，逗号分隔可多播</li>
 * </ul>
 */
public class SimpleExpressionEngine implements IExpressionEngine {

    @Override
    public boolean evaluate(String expr, RuleMessage msg) {
        if (expr == null || expr.trim().isEmpty()) {
            return true;
        }
        Map<String, String> data = payloadAsMap(msg.getPayload());
        String[] parts = splitOperator(expr);
        if (parts == null) {
            return false;
        }
        String left = resolve(parts[0], msg, data);
        String right = parts[2].trim();
        String op = parts[1];
        if (left == null) {
            return false;
        }
        Double ln = asDouble(left);
        Double rn = asDouble(right);
        if (ln != null && rn != null) {
            int cmp = ln.compareTo(rn);
            return compareNumeric(cmp, op);
        }
        int cmp = left.compareTo(right);
        if ("==".equals(op) || "=".equals(op)) {
            return cmp == 0;
        }
        if ("!=".equals(op)) {
            return cmp != 0;
        }
        return false;
    }

    @Override
    public String transform(String expr, RuleMessage msg) {
        Map<String, String> data = new LinkedHashMap<String, String>(payloadAsMap(msg.getPayload()));
        if (expr != null && !expr.trim().isEmpty()) {
            for (String cmd : expr.split(";")) {
                String c = cmd.trim();
                if (c.isEmpty()) {
                    continue;
                }
                int eq = c.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = c.substring(0, eq).trim();
                String value = c.substring(eq + 1).trim();
                if (key.startsWith("set:")) {
                    msg.getMetaData().put(key.substring(4), render(value, msg, data));
                } else {
                    data.put(key, render(value, msg, data));
                }
            }
        }
        return toPayload(data);
    }

    @Override
    public Set<String> route(String expr, RuleMessage msg) {
        Set<String> out = new LinkedHashSet<String>();
        if (expr == null || expr.trim().isEmpty()) {
            out.add("Success");
            return out;
        }
        String key = expr.trim();
        String raw = msg.getMetaData().get(key);
        if (raw == null) {
            raw = payloadAsMap(msg.getPayload()).get(key);
        }
        if (raw == null || raw.isEmpty()) {
            out.add("Success");
            return out;
        }
        for (String r : raw.split(",")) {
            out.add(r.trim());
        }
        return out;
    }

    /** 支持 ${metaKey} 与 $[field] 两种取值模板。 */
    private String render(String value, RuleMessage msg, Map<String, String> data) {
        String v = value;
        if (v.startsWith("${") && v.endsWith("}")) {
            String k = v.substring(2, v.length() - 1);
            String meta = msg.getMetaData().get(k);
            return meta == null ? "" : meta;
        }
        if (v.startsWith("$[") && v.endsWith("]")) {
            String k = v.substring(2, v.length() - 1);
            String f = data.get(k);
            return f == null ? "" : f;
        }
        return v;
    }

    private String resolve(String token, RuleMessage msg, Map<String, String> data) {
        String t = token.trim();
        if (t.startsWith("meta.")) {
            return msg.getMetaData().get(t.substring(5));
        }
        return data.get(t);
    }

    private String[] splitOperator(String expr) {
        String[] ops = {">=", "<=", "!=", "==", ">", "<", "="};
        for (String op : ops) {
            int idx = expr.indexOf(op);
            if (idx > 0) {
                return new String[]{expr.substring(0, idx), op, expr.substring(idx + op.length())};
            }
        }
        return null;
    }

    private boolean compareNumeric(int cmp, String op) {
        if (">".equals(op)) {
            return cmp > 0;
        }
        if (">=".equals(op)) {
            return cmp >= 0;
        }
        if ("<".equals(op)) {
            return cmp < 0;
        }
        if ("<=".equals(op)) {
            return cmp <= 0;
        }
        if ("==".equals(op) || "=".equals(op)) {
            return cmp == 0;
        }
        if ("!=".equals(op)) {
            return cmp != 0;
        }
        return false;
    }

    private Double asDouble(String s) {
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** payload 兼容 JSON 与 k=v;k=v 两种形式。 */
    @SuppressWarnings("unchecked")
    private Map<String, String> payloadAsMap(String payload) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (payload == null || payload.isEmpty()) {
            return out;
        }
        String p = payload.trim();
        if (p.startsWith("{")) {
            try {
                Map<String, Object> obj = JsonUtil.parseObject(p);
                for (Map.Entry<String, Object> e : obj.entrySet()) {
                    out.put(e.getKey(), JsonUtil.asString(e.getValue()));
                }
                return out;
            } catch (RuntimeException ignored) {
                // 回退到 k=v 解析
            }
        }
        for (String pair : p.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return out;
    }

    private String toPayload(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (!first) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }
}
