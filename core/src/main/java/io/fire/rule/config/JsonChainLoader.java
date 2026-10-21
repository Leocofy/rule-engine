package io.fire.rule.config;

import io.fire.rule.chain.RuleChain;
import io.fire.rule.spi.*;
import io.fire.rule.util.JsonUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则链 JSON 定义加载器。
 *
 * <p>配置格式（与 eKuiper 图规则 API 同构，可直接映射成拖拽画布）：
 * <pre>
 * {
 *   "id": "fireAlarm",
 *   "name": "消防告警链",
 *   "nodes": {
 *     "src":    {"type": "log", "props": {"prefix": "收到"}},
 *     "filter": {"type": "filter", "props": {"expr": "temp &gt; 80"}},
 *     "alarm":  {"type": "log", "props": {"prefix": "告警"}}
 *   },
 *   "edges": {
 *     "src":    [{"to": "filter"}],
 *     "filter": [{"to": "alarm", "relation": "True"}]
 *   },
 *   "entry": ["src"]
 * }
 * </pre>
 *
 * <p>edge 未声明 relation 时默认 "Success"——
 * 这让"串联"场景写起来最简洁，"分支"场景再显式指定关系名。
 */
public final class JsonChainLoader {

    private final INodeRegistry registry;

    public JsonChainLoader(INodeRegistry registry) {
        this.registry = registry;
    }

    public RuleChain load(String json) throws NodeException {
        Map<String, Object> root = JsonUtil.parseObject(json);
        String id = JsonUtil.asString(root.get("id"));
        String name = JsonUtil.asString(root.get("name"));
        if (id == null || id.isEmpty()) {
            throw new NodeException("规则链缺少 id", true);
        }
        RuleChain chain = new RuleChain(id, name == null ? id : name);

        Map<String, Object> nodesDef = JsonUtil.asObject(root.get("nodes"));
        if (nodesDef == null || nodesDef.isEmpty()) {
            throw new NodeException("规则链 " + id + " 没有定义任何节点", true);
        }

        for (Map.Entry<String, Object> entry : nodesDef.entrySet()) {
            String nodeId = entry.getKey();
            Map<String, Object> nodeDef = JsonUtil.asObject(entry.getValue());
            String type = JsonUtil.asString(nodeDef.get("type"));
            if (type == null) {
                throw new NodeException("节点 " + nodeId + " 缺少 type", true);
            }
            RuleNode node = registry.get(type);

            Map<String, String> props = new HashMap<String, String>();
            Object rawProps = nodeDef.get("props");
            if (rawProps != null) {
                for (Map.Entry<String, Object> p : JsonUtil.asObject(rawProps).entrySet()) {
                    props.put(p.getKey(), JsonUtil.asString(p.getValue()));
                }
            }
            NodeConfig config = new NodeConfig(props);
            node.init(null, config);   // 无上下文初始化：仅解析配置、编译脚本
            chain.addNode(nodeId, type, node);
        }

        // 边：默认关系为 Success
        Object edgesRaw = root.get("edges");
        if (edgesRaw != null) {
            for (Map.Entry<String, Object> e : JsonUtil.asObject(edgesRaw).entrySet()) {
                String from = e.getKey();
                for (Object edgeObj : JsonUtil.asArray(e.getValue())) {
                    Map<String, Object> edge = JsonUtil.asObject(edgeObj);
                    String to = JsonUtil.asString(edge.get("to"));
                    String relation = JsonUtil.asString(edge.get("relation"));
                    if (relation == null || relation.isEmpty()) {
                        relation = INodeContext.REL_SUCCESS;
                    }
                    if (to == null || !chain.hasNode(to)) {
                        throw new NodeException("边指向了不存在的节点: " + from + " -> " + to, true);
                    }
                    chain.connect(from, relation, to);
                }
            }
        }

        for (Object entry : JsonUtil.asArray(root.get("entry"))) {
            String nodeId = JsonUtil.asString(entry);
            if (chain.hasNode(nodeId)) {
                chain.addEntry(nodeId);
            }
        }
        if (chain.entryNodes().isEmpty()) {
            // 未声明入口时，取第一个节点作为入口，便于快速试验
            List<String> first = new ArrayList<String>(nodesDef.keySet());
            chain.addEntry(first.get(0));
        }
        return chain;
    }
}
