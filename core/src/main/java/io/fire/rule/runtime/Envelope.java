package io.fire.rule.runtime;

import io.fire.rule.model.RuleMessage;

import java.util.Set;
import java.util.UUID;

/**
 * 队列中的消息信封：把引擎内部消息与具体消息队列解耦。
 */
public final class Envelope {

    private final String id;
    private final RuleMessage message;
    private final Object ackHandle;
    private final Set<String> relations;
    private final String chainId;
    private final String nodeId;

    public Envelope(RuleMessage message) {
        this(UUID.randomUUID().toString(), message, null, null, null, null);
    }

    public Envelope(String id, RuleMessage message, Object ackHandle) {
        this(id, message, ackHandle, null, null, null);
    }

    public Envelope(String id, RuleMessage message, Object ackHandle, Set<String> relations) {
        this(id, message, ackHandle, relations, null, null);
    }

    public Envelope(String id, RuleMessage message, Object ackHandle, Set<String> relations,
                    String chainId, String nodeId) {
        this.id = id;
        this.message = message;
        this.ackHandle = ackHandle;
        this.relations = relations;
        this.chainId = chainId;
        this.nodeId = nodeId;
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

    public String getChainId() {
        return chainId;
    }

    public String getNodeId() {
        return nodeId;
    }
}
