package io.fire.rule.spring.flowgram.workflow;

import java.util.Map;

/**
 * FlowGram 连线：{@code {sourceNodeID, targetNodeID, sourcePortID?, targetPortID?}}。
 *
 * <p>{@code sourcePortID} 取代旧的 relation 概念——它就是节点的输出端口 ID；
 * 为 {@code null} 时走默认端口（串联场景）。
 *
 * @author rule-engine
 */
public final class WorkflowEdge {

    private final String sourceNodeID;
    private final String targetNodeID;
    private final String sourcePortID;
    private final String targetPortID;

    public WorkflowEdge(String sourceNodeID, String targetNodeID, String sourcePortID, String targetPortID) {
        this.sourceNodeID = sourceNodeID;
        this.targetNodeID = targetNodeID;
        this.sourcePortID = sourcePortID;
        this.targetPortID = targetPortID;
    }

    @SuppressWarnings("unchecked")
    public static WorkflowEdge from(Object raw) {
        Map<String, Object> map = (Map<String, Object>) raw;
        return new WorkflowEdge(
                str(map.get("sourceNodeID")),
                str(map.get("targetNodeID")),
                str(map.get("sourcePortID")),
                str(map.get("targetPortID")));
    }

    public String sourceNodeID() {
        return sourceNodeID;
    }

    public String targetNodeID() {
        return targetNodeID;
    }

    public String sourcePortID() {
        return sourcePortID;
    }

    public String targetPortID() {
        return targetPortID;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
