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

    public static final NodeConfig EMPTY = new NodeConfig(Collections.<String, String>emptyMap());

    private final Map<String, String> props;

    public NodeConfig(Map<String, String> props) {
        this.props = props == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(props);
    }

    public String get(String key) {
        return props.get(key);
    }

    public String getOrDefault(String key, String def) {
        String v = props.get(key);
        return v == null ? def : v;
    }

    public int getInt(String key, int def) {
        String v = props.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        String v = props.get(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    public Map<String, String> props() {
        return Collections.unmodifiableMap(props);
    }
}
