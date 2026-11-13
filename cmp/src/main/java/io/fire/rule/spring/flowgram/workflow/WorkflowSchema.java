package io.fire.rule.spring.flowgram.workflow;

import io.fire.rule.core.util.JsonUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * FlowGram 工作流schema（权威链格式）：{@code {nodes[], edges[], ...}}。
 *
 * <p>画布与后端唯一的图契约。可选携带 {@code id / name}；缺省时由加载器以首个
 * {@code start} 节点或随机 UUID 兜底。Loop 节点的子图由 {@link WorkflowNode#blocks()}
 * 递归承载，不再使用旧的 {@code nodes-map / edges-relation / entry} 结构。
 *
 * @author rule-engine
 */
public final class WorkflowSchema {

    public static final String CONTROL_TYPE = "控制流";

    private final Map<String, Object> root;
    private final List<WorkflowNode> nodes;
    private final List<WorkflowEdge> edges;

    @SuppressWarnings("unchecked")
    private WorkflowSchema(Map<String, Object> root) {
        this.root = root;
        this.nodes = new ArrayList<>();
        Object nodesRaw = root.get("nodes");
        if (nodesRaw instanceof List) {
            for (Object n : (List<?>) nodesRaw) {
                if (n instanceof Map) {
                    nodes.add(new WorkflowNode((Map<String, Object>) n));
                }
            }
        }
        this.edges = new ArrayList<>();
        Object edgesRaw = root.get("edges");
        if (edgesRaw instanceof List) {
            for (Object e : (List<?>) edgesRaw) {
                if (e instanceof Map) {
                    edges.add(WorkflowEdge.from(e));
                }
            }
        }
    }

    public static WorkflowSchema parse(Object raw) {
        return new WorkflowSchema(JsonUtil.asObject(raw));
    }

    public Map<String, Object> root() {
        return root;
    }

    public String id() {
        Object v = root.get("id");
        return v == null ? null : String.valueOf(v);
    }

    public String name() {
        Object v = root.get("name");
        return v == null ? null : String.valueOf(v);
    }

    public List<WorkflowNode> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<WorkflowEdge> edges() {
        return Collections.unmodifiableList(edges);
    }
}
