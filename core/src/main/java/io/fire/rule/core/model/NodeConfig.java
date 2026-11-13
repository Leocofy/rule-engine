package io.fire.rule.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点配置：键值对形式，与具体序列化方式解耦。
 *
 * <p>刻意不使用强类型配置类——规则链存的是 JSON，节点由配置驱动，
 * 键值对能让节点库与配置格式解耦，便于后期接入数据库 / Nacos / 自定义格式。
 */
public final class NodeConfig {

    public static final NodeConfig EMPTY = new NodeConfig(Collections.emptyMap());

    private final Map<String, Object> props;

    public NodeConfig(Map<String, Object> props) {
        this.props = props == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(props);
    }

    public Object get(String key) {
        return props.get(key);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getObj(String key) {
        Object o = props.get(key);
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getArray(String key) {
        Object o = props.get(key);
        return o instanceof List ? (List<T>) o : null;
    }

    /**
     * 配置里是否存在该键（值可为 null）。
     */
    public boolean has(String key) {
        return props.containsKey(key);
    }

    public String getString(String key) {
        Object v = props.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public String getString(String key, String def) {
        Object v = props.get(key);
        return v == null ? def : String.valueOf(v);
    }

    /**
     * 数字读取统一先走 {@link Number}：规则链 JSON 里的数字可能被解析成 Double，
     * 声明式参数也会把 "80" 规范化成 Long，用字符串解析的方式会静默丢掉默认值。
     */
    public int getInt(String key, int def) {
        Number n = getNumber(key);
        return n == null ? def : n.intValue();
    }

    public long getLong(String key, long def) {
        Number n = getNumber(key);
        return n == null ? def : n.longValue();
    }

    public double getDouble(String key, double def) {
        Number n = getNumber(key);
        return n == null ? def : n.doubleValue();
    }

    public boolean getBool(String key, boolean def) {
        Object v = props.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String text = String.valueOf(v).trim().toLowerCase();
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text) || "on".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text) || "off".equals(text)) {
            return false;
        }
        return def;
    }

    private Number getNumber(String key) {
        Object v = props.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return (Number) v;
        }
        String text = String.valueOf(v).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Map<String, Object> props() {
        return Collections.unmodifiableMap(props);
    }
}
