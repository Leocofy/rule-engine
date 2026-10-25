package io.fire.rule.model;

import java.util.UUID;

/**
 * 规则引擎中流转的消息：纯业务数据，不可变。
 *
 * <p>只承载业务关注的数据（id / type / originator / payload / metadata），
 * 引擎内部状态（chainId / nodeId / stack / callback）由 ExecutionContext 承载。
 *
 * <p>任何修改都通过 {@link #transform()} 产生新实例。
 */
public final class RuleMessage {

    private final String id;
    private final String type;
    private final String originator;
    private final String payload;
    private final MetaData metadata;

    private RuleMessage(Builder b) {
        this.id = b.id != null ? b.id : UUID.randomUUID().toString();
        this.type = b.type;
        this.originator = b.originator;
        this.payload = b.payload;
        this.metadata = b.metadata != null ? b.metadata : new MetaData();
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

    public String getOriginator() {
        return originator;
    }

    public String getPayload() {
        return payload;
    }

    public MetaData getMetaData() {
        return metadata;
    }

    @Override
    public String toString() {
        return "RuleMessage{id=" + id + ", type=" + type + ", originator=" + originator + "}";
    }

    public static class Builder {
        protected String id;
        protected String type;
        protected String originator;
        protected String payload;
        protected MetaData metadata;

        protected Builder() {
        }

        protected Builder(RuleMessage m) {
            this.id = m.id;
            this.type = m.type;
            this.originator = m.originator;
            this.payload = m.payload;
            this.metadata = m.metadata;
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

        public RuleMessage build() {
            return new RuleMessage(this);
        }
    }

    /**
     * 变体构造器：metadata 只在显式指定时拷贝，否则共享引用。
     */
    public static final class Transformer extends Builder {

        private Transformer(RuleMessage source) {
            super.id = source.id;
            super.type = source.type;
            super.originator = source.originator;
            super.payload = source.payload;
            super.metadata = source.metadata;
        }

        @Override
        public Builder metaData(MetaData metadata) {
            super.metadata = metadata == null ? null : metadata.copy();
            return this;
        }
    }
}
