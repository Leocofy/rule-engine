package io.fire.rule.spring.flowgram.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * FlowGram 节点：{@code {id, type, meta, data, blocks?, edges?}}。
 *
 * <p>{@code data} 原样保留（引擎对配置透明），其中的特殊键
 * {@code title / inputsValues / inputs / outputs / conditions / loopFor / loopOutputs}
 * 由执行器按需读取。Loop 的子图放在 {@code blocks[]} + 内联 {@code edges[]}。
 *
 * @author rule-engine
 */
public final class WorkflowNode {

    private final String id;
    private final String type;
    private final Map<String, Object> meta;
    private final Map<String, Object> data;
    private final List<WorkflowNode> blocks;
    private final List<WorkflowEdge> edges;

    @SuppressWarnings("unchecked")
    public WorkflowNode(Map<String, Object> raw) {
        this.id = str(raw.get("id"));
        this.type = str(raw.get("type"));
        Object metaRaw = raw.get("meta");
        this.meta = metaRaw instanceof Map ? (Map<String, Object>) metaRaw : Collections.emptyMap();
        Object dataRaw = raw.get("data");
        this.data = dataRaw instanceof Map ? (Map<String, Object>) dataRaw : Collections.emptyMap();
        Object blocksRaw = raw.get("blocks");
        if (blocksRaw instanceof List) {
            List<WorkflowNode> bs = new ArrayList<>();
            for (Object b : (List<?>) blocksRaw) {
                if (b instanceof Map) {
                    bs.add(new WorkflowNode((Map<String, Object>) b));
                }
            }
            this.blocks = bs;
        } else {
            this.blocks = Collections.emptyList();
        }
        Object edgesRaw = raw.get("edges");
        if (edgesRaw instanceof List) {
            List<WorkflowEdge> es = new ArrayList<>();
            for (Object e : (List<?>) edgesRaw) {
                if (e instanceof Map) {
                    es.add(WorkflowEdge.from(e));
                }
            }
            this.edges = es;
        } else {
            this.edges = Collections.emptyList();
        }
    }

    public String id() {
        return id;
    }

    public String type() {
        return type;
    }

    public Map<String, Object> meta() {
        return meta;
    }

    public Map<String, Object> data() {
        return data;
    }

    public List<WorkflowNode> blocks() {
        return blocks;
    }

    public List<WorkflowEdge> edges() {
        return edges;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
