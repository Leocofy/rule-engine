package io.fire.rule.cluster;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 跨节点传输的消息载体。
 *
 * <p><b>为什么不能直接用 RuleMessage</b>：
 * 跨实例投递时必须把"这条消息要走哪些关系"一起传过去。
 * 关系是"这一跳的路由指令"，不属于消息本身，
 * 所以放在传输层而不是消息里——这是与 ThingsBoard proto 中
 * {@code relationTypes} 字段同构的设计。
 */
public final class TransportMessage {

    private String id;
    private String type;
    private String originator;
    private String payload;
    private byte[] metaData;      // 序列化的 MetaData
    private String chainId;
    private String nodeId;
    private Set<String> relations;

    public TransportMessage() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    /** 分区键：必须与本地 lane 使用同一个键，否则顺序保证会断。 */
    public String getOriginator() {
        return originator;
    }

    public void setOriginator(String originator) {
        this.originator = originator;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public byte[] getMetaData() {
        return metaData;
    }

    public void setMetaData(byte[] metaData) {
        this.metaData = metaData;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    /** 恢复位置：从哪个节点继续（不是重新执行它）。 */
    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    /** 该节点输出的关系名，消费端据此继续路由。 */
    public Set<String> getRelations() {
        return relations;
    }

    public void setRelations(Set<String> relations) {
        this.relations = relations == null
                ? new LinkedHashSet<String>() : new LinkedHashSet<String>(relations);
    }
}
