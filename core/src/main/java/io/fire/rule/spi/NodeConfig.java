package io.fire.rule.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
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

    public String getString(String key) {
        Object v = props.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public String getString(String key, String def) {
        Object v = props.get(key);
        return v == null ? def : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T def) {
        Object v = props.get(key);
        return v == null ? def : (T) v;
    }

    public int getInt(String key, int def) {
        Object v = props.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        Object v = props.get(key);
        return v == null ? def : Boolean.parseBoolean(v.toString().trim());
    }

    public Map<String, Object> props() {
        return Collections.unmodifiableMap(props);
    }
}
