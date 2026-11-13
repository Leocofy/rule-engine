package io.fire.rule.core.model;

import io.fire.rule.core.util.JsonUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 规则引擎中流转的<b>信封</b>：只携带身份与路由所需的元信息，业务数据全部收敛到 {@link ExecutionState}。
 *
 * <p>统一 State 模型（方案 A）：
 * <ul>
 *   <li>信封字段：{@code id / type / originator / state}。{@code originator} 是分区键，必须留在信封上随行。</li>
 *   <li>数据状态：{@link ExecutionState}（入口负载 + 命名空间产出 + 元数据 + 作用域），不可变、可序列化。</li>
 *   <li>引擎内部状态（chainId / nodeId / stack / callback）由 {@code ExecutionContext} 承载，不在此。</li>
 * </ul>
 *
 * <p>任何修改都通过 {@link #withState(ExecutionState)} 产生新实例（信封各字段沿用，替换状态快照）。
 */
public final class RuleMessage {

    private final String id;
    private final String type;
    private final String originator;
    private final ExecutionState state;

    private RuleMessage(Builder b) {
        this.id = b.id != null ? b.id : UUID.randomUUID().toString();
        this.type = b.type;
        this.originator = b.originator;
        this.state = b.state != null ? b.state
                : ExecutionState.start(parseEntry(b.payload), b.meta);
    }

    public static Builder newMessage() {
        return new Builder();
    }

    /** 以新的状态快照派生新信封（id/type/originator 沿用）。 */
    public RuleMessage withState(ExecutionState newState) {
        return newMessage()
                .id(id)
                .type(type)
                .originator(originator)
                .state(newState == null ? ExecutionState.EMPTY : newState)
                .build();
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getOriginator() {
        return originator;
    }

    public ExecutionState getState() {
        return state;
    }

    @Override
    public String toString() {
        return "RuleMessage{id=" + id + ", type=" + type + ", originator=" + originator + "}";
    }

    public static class Builder {
        private String id;
        private String type;
        private String originator;
        /** 显式给出的状态快照；非空时优先于 {@code payload}/{@code meta}。 */
        private ExecutionState state;
        /** 便捷构造：原始负载 JSON 字符串（引擎入口 / 序列化还原使用）。 */
        private String payload;
        /** 便捷构造：初始元数据。 */
        private Map<String, Object> meta;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder originator(String originator) {
            this.originator = originator;
            return this;
        }

        public Builder state(ExecutionState state) {
            this.state = state;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder metaData(Map<String, Object> meta) {
            this.meta = meta;
            return this;
        }

        public RuleMessage build() {
            return new RuleMessage(this);
        }
    }

    /**
     * 把原始负载字符串解析成入口字段映射：合法 JSON 对象 → 原样；
     * 非对象 / 非法 → 包装为 {@code {_raw: ...}}（与旧命名空间约定一致）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseEntry(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = JsonUtil.parseAny(payload);
            if (parsed instanceof Map) {
                return new LinkedHashMap<>((Map<String, Object>) parsed);
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("_raw", parsed);
            return wrapper;
        } catch (Exception e) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("_raw", payload);
            return wrapper;
        }
    }
}
