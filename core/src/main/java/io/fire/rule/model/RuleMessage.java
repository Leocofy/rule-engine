package io.fire.rule.model;

import java.util.UUID;

/**
 * 规则引擎中流转的消息：不可变、可序列化。
 *
 * <p><b>为什么必须不可变</b>：一条消息可能被 fan-out 成多条并行处理，
 * 若共享可变状态会互相污染。任何修改都通过 {@link #transform()} 产生新实例。
 *
 * <p><b>三种构造方式的语义差异（重要）</b>：
 * <ul>
 *   <li>{@link #newMessage()} —— 全新消息，metadata 为空</li>
 *   <li>{@link #transform()} —— 变体，metadata 仅在显式指定时才携带，切链自动清空 nodeId</li>
 *   <li>{@link #copy()} —— 全量拷贝后再改，用于嵌套链等需要保留上下文的场景</li>
 * </ul>
 *
 * <p>{@code callback} 与 {@code stack} 属于"一次处理过程内的运行时状态"，
 * 序列化时可丢弃，重新消费时重建。
 */
public final class RuleMessage {

    private final String id;
    private final String type;
    private final String originator;
    private final String payload;
    private final MetaData metadata;
    private final String chainId;
    private final String nodeId;
    private final ProcessingStack stack;
    private final transient PackCallback callback;

    private RuleMessage(Builder b) {
        this.id = b.id != null ? b.id : UUID.randomUUID().toString();
        this.type = b.type != null ? b.type : "UNKNOWN";
        this.originator = b.originator;
        this.payload = b.payload;
        this.metadata = b.metadata != null ? b.metadata : new MetaData();
        this.chainId = b.chainId;
        this.nodeId = b.nodeId;
        this.stack = b.stack != null ? b.stack : new ProcessingStack();
        this.callback = b.callback != null ? b.callback : PackCallback.EMPTY;
    }

    public static Builder newMessage() {
        return new Builder();
    }

    public Builder transform() {
        return new Transformer(this);
    }

    public Builder copy() {
        return new Builder(this);
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    /** 分区键：同一 originator 的消息必须落到同一处理通道，保证顺序。 */
    public String getOriginator() {
        return originator;
    }

    public String getPayload() {
        return payload;
    }

    public MetaData getMetaData() {
        return metadata;
    }

    public String getChainId() {
        return chainId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public PackCallback getCallback() {
        return callback;
    }

    /** 批次超时/取消后返回 false，异步节点应据此提前放弃。 */
    public boolean isValid() {
        return callback.isValid();
    }

    public void pushToStack(String chainId, String nodeId) {
        stack.push(chainId, nodeId);
    }

    public boolean alreadyInStack(String chainId, String nodeId) {
        return stack.contains(chainId, nodeId);
    }

    public String[] popFromStack() {
        return stack.pop();
    }

    @Override
    public String toString() {
        return "RuleMessage{id=" + id + ", type=" + type + ", originator=" + originator
                + ", node=" + chainId + "/" + nodeId + "}";
    }

    public static class Builder {
        protected String id;
        protected String type;
        protected String originator;
        protected String payload;
        protected MetaData metadata;
        protected String chainId;
        protected String nodeId;
        protected ProcessingStack stack;
        protected PackCallback callback;

        protected Builder() {
        }

        protected Builder(RuleMessage m) {
            this.id = m.id;
            this.type = m.type;
            this.originator = m.originator;
            this.payload = m.payload;
            this.metadata = m.metadata;
            this.chainId = m.chainId;
            this.nodeId = m.nodeId;
            this.stack = m.stack;
            this.callback = m.callback;
        }

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

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder metaData(MetaData metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder copyMetaData(MetaData metadata) {
            this.metadata = metadata.copy();
            return this;
        }

        public Builder chainId(String chainId) {
            this.chainId = chainId;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder resetNodeId() {
            return nodeId(null);
        }

        public Builder stack(ProcessingStack stack) {
            this.stack = stack;
            return this;
        }

        public Builder callback(PackCallback callback) {
            this.callback = callback;
            return this;
        }

        public RuleMessage build() {
            return new RuleMessage(this);
        }
    }

    /**
     * 变体构造器，承载三条关键语义：
     * <ol>
     *   <li>metadata 只在显式指定时拷贝，否则新消息上下文是干净的</li>
     *   <li>切换 chainId 时自动清空 nodeId（跨链时旧节点 id 无意义）</li>
     *   <li>build 时总是拷贝 stack，保证 fan-out 出的多条消息路径互不干扰</li>
     * </ol>
     */
    public static final class Transformer extends Builder {

        private final RuleMessage source;

        private Transformer(RuleMessage source) {
            this.source = source;
            super.id = source.id;
            super.type = source.type;
            super.originator = source.originator;
            super.payload = source.payload;
            super.metadata = source.metadata;
            super.chainId = source.chainId;
            super.nodeId = source.nodeId;
            super.stack = source.stack;
            super.callback = source.callback;
        }

        @Override
        public Builder metaData(MetaData metadata) {
            super.metadata = metadata == null ? null : metadata.copy();
            return this;
        }

        @Override
        public Builder chainId(String chainId) {
            super.chainId = chainId;
            super.nodeId = null;
            return this;
        }

        @Override
        public RuleMessage build() {
            if (super.stack != null) {
                super.stack = super.stack.copy();
            }
            return new RuleMessage(this);
        }
    }
}
