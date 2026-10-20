package io.fire.rule.runtime;

import io.fire.rule.model.RuleMessage;

import java.util.Set;
import java.util.UUID;

/**
 * 队列中的消息信封：把引擎内部消息与具体消息队列解耦。
 *
 * <p>承载三类信息：
 * <ul>
 *   <li>引擎内部的 {@link RuleMessage}</li>
 *   <li>批次内唯一 id，用于 pending/success/failed 三个映射表追踪</li>
 *   <li>来源队列的确认句柄（由具体 MessageSource 实现决定语义）</li>
 * </ul>
 */
public final class Envelope {

    private final String id;
    private final RuleMessage message;
    private final Object ackHandle;
    /**
     * 跨节点恢复时携带的关系名。
     *
     * <p>单机时为 null（从入口或指定节点开始正常执行）；
     * 分布式时由对端序列化带过来，表示"从 nodeId 的这些关系继续路由"。
     */
    private final Set<String> relations;

    public Envelope(RuleMessage message) {
        this(UUID.randomUUID().toString(), message, null, null);
    }

    public Envelope(String id, RuleMessage message, Object ackHandle) {
        this(id, message, ackHandle, null);
    }

    public Envelope(String id, RuleMessage message, Object ackHandle, Set<String> relations) {
        this.id = id;
        this.message = message;
        this.ackHandle = ackHandle;
        this.relations = relations;
    }

    public Set<String> getRelations() {
        return relations;
    }

    public String getId() {
        return id;
    }

    public RuleMessage getMessage() {
        return message;
    }

    public Object getAckHandle() {
        return ackHandle;
    }
}
