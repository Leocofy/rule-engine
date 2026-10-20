package io.fire.rule.chain;

import io.fire.rule.spi.RuleNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则链：节点 + 命名关系构成的有向图。
 *
 * <p><b>核心设计：用"关系名"代替条件分支。</b>
 * 节点不写 {@code if (x) 调A else 调B}，而是喊一句"这条消息属于 Success / Failure / 有隐患"，
 * 由引擎查邻接表决定发给谁。这样编排可配置化，节点与流程彻底解耦。
 *
 * <p>邻接表结构：{@code (nodeId, relation) -> 下游节点列表}
 */
public final class RuleChain {

    private final String id;
    private final String name;
    private final Map<String, RuleNode> nodes = new LinkedHashMap<String, RuleNode>();
    private final Map<String, String> nodeTypes = new LinkedHashMap<String, String>();
    private final Map<String, Map<String, List<String>>> adjacency =
            new HashMap<String, Map<String, List<String>>>();
    private final List<String> entryNodes = new ArrayList<String>();

    public RuleChain(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public RuleChain addNode(String nodeId, String type, RuleNode node) {
        nodes.put(nodeId, node);
        nodeTypes.put(nodeId, type);
        return this;
    }

    /** 声明一条带关系名的边。同一关系可连多个下游，天然支持 fan-out。 */
    public RuleChain connect(String fromNodeId, String relation, String toNodeId) {
        Map<String, List<String>> rels = adjacency.computeIfAbsent(fromNodeId, k -> new HashMap<>());
        rels.computeIfAbsent(relation, k -> new ArrayList<>()).add(toNodeId);
        return this;
    }

    public RuleChain addEntry(String nodeId) {
        entryNodes.add(nodeId);
        return this;
    }

    public List<String> entryNodes() {
        return Collections.unmodifiableList(entryNodes);
    }

    public RuleNode node(String nodeId) {
        return nodes.get(nodeId);
    }

    public String nodeType(String nodeId) {
        return nodeTypes.get(nodeId);
    }

    public boolean hasNode(String nodeId) {
        return nodes.containsKey(nodeId);
    }

    /** 查邻接表：找不到下游返回空列表。 */
    public List<String> downstream(String nodeId, String relation) {
        Map<String, List<String>> rels = adjacency.get(nodeId);
        if (rels == null) {
            return Collections.emptyList();
        }
        List<String> targets = rels.get(relation);
        return targets == null ? Collections.<String>emptyList() : Collections.unmodifiableList(targets);
    }

    public void destroy() {
        for (RuleNode node : nodes.values()) {
            node.destroy();
        }
    }
}
