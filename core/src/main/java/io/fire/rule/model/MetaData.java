package io.fire.rule.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息元数据：承载上下文信息（设备名、设备类型、上游节点写入的中间结果等）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>值统一为 String，保证跨进程序列化时类型确定（对接 Kafka / RabbitMQ 时不需要额外约定）</li>
 *   <li>底层用 LinkedHashMap 保证遍历顺序稳定，便于日志与快照对比</li>
 *   <li>提供 copy() 用于消息 fan-out 时隔离，避免多条派生消息互相污染</li>
 * </ul>
 */
public final class MetaData {

    public static final MetaData EMPTY = new MetaData(Collections.<String, String>emptyMap());

    private final Map<String, String> data;

    public MetaData() {
        this.data = new LinkedHashMap<>();
    }

    public MetaData(Map<String, String> data) {
        this.data = new LinkedHashMap<>();
        if (data != null) {
            this.data.putAll(data);
        }
    }

    public String get(String key) {
        return data.get(key);
    }

    public void put(String key, String value) {
        if (key != null && value != null) {
            data.put(key, value);
        }
    }

    public Map<String, String> values() {
        return Collections.unmodifiableMap(data);
    }

    public MetaData copy() {
        return new MetaData(data);
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    @Override
    public String toString() {
        return data.toString();
    }
}
