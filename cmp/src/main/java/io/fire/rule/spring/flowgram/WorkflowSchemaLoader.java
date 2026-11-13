package io.fire.rule.spring.flowgram;

import io.fire.rule.core.chain.RuleChain;
import io.fire.rule.core.config.IChainLoader;
import io.fire.rule.core.config.INodeConfigBinder;
import io.fire.rule.core.model.NodeConfig;
import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.core.spi.INodeRegistry;
import io.fire.rule.core.spi.NodeException;
import io.fire.rule.core.spi.RuleNode;
import io.fire.rule.core.util.JsonUtil;
import io.fire.rule.spring.base.NodeConfigAware;
import io.fire.rule.spring.flowgram.workflow.WorkflowEdge;
import io.fire.rule.spring.flowgram.workflow.WorkflowNode;
import io.fire.rule.spring.flowgram.workflow.WorkflowSchema;

import java.util.*;

/**
 * FlowGram WorkflowSchema 装载器：把画布导出的 {@code {nodes[], edges[]}} 建成运行期 {@link RuleChain}。
 *
 * <p>取代旧的 {@code JsonChainLoader}（nodes-map / edges-relation / entry 结构）：
 * <ul>
 *   <li>节点 {@code data} 原样存入 {@link NodeConfig}，特殊键（inputsValues/conditions/loopFor...）供执行器读取</li>
 *   <li>控制流节点（start/end/condition/loop）用引擎内置实例，业务节点走 {@link INodeRegistry}</li>
 *   <li>边的 {@code sourcePortID} 即端口，缺省走 {@link INodeContext#PORT_DEFAULT}</li>
 *   <li>校验：恰有一个 start 作唯一入口、至少一个 end</li>
 *   <li>Loop 的 {@code blocks} 递归建成内联子链，挂到 {@link RuleChain#addSubChain}，
 *       并在 Loop 的 {@link NodeConfig} 里写 {@code blocksChainId}</li>
 * </ul>
 *
 * @author rule-engine
 */
public final class WorkflowSchemaLoader implements IChainLoader {

    private final INodeRegistry registry;
    private final INodeConfigBinder configBinder;

    public WorkflowSchemaLoader(INodeRegistry registry) {
        this(registry, null);
    }

    public WorkflowSchemaLoader(INodeRegistry registry, INodeConfigBinder configBinder) {
        this.registry = registry;
        this.configBinder = configBinder;
    }

    @Override
    public RuleChain load(Object chain) throws NodeException {
        WorkflowSchema schema = WorkflowSchema.parse(chain);
        String id = schema.id();
        if (id == null || id.isEmpty()) {
            throw new NodeException("工作流缺少 id");
        }
        String name = schema.name();
        return build(id, name == null ? id : name, schema.nodes(), schema.edges(), true);
    }

    private RuleChain build(String chainId, String chainName, List<WorkflowNode> nodes,
                            List<WorkflowEdge> edges, boolean topLevel) throws NodeException {
        if (nodes == null || nodes.isEmpty()) {
            throw new NodeException("规则链 " + chainId + " 没有定义任何节点", true);
        }
        RuleChain chain = new RuleChain(chainId, chainName);

        List<String> starts = new ArrayList<>();
        int endCount = 0;
        for (WorkflowNode node : nodes) {
            String nodeId = node.id();
            String type = node.type();
            if (nodeId == null || nodeId.isEmpty()) {
                throw new NodeException("节点缺少 id（链 " + chainId + "）", true);
            }
            if (type == null || type.isEmpty()) {
                throw new NodeException("节点 " + nodeId + " 缺少 type", true);
            }
            if (WorkflowConstants.TYPE_START.equals(type)) {
                starts.add(nodeId);
            } else if (WorkflowConstants.TYPE_END.equals(type)) {
                endCount++;
            }

            RuleNode impl = resolveNode(type);
            NodeConfig config = buildConfig(chain, nodeId, type, node);
            chain.addNode(nodeId, type, impl, config);
            if (configBinder != null && impl instanceof NodeConfigAware) {
                try {
                    chain.putCompiledConfig(nodeId,
                            configBinder.bind(config, ((NodeConfigAware) impl).configType()));
                } catch (RuntimeException ignored) {
                    // 业务 Config 的真实值依赖 ExecutionState，预编译失败时运行期 fallback
                }
            }
        }

        // 边：sourcePortID 即端口，缺省走默认端口
        Set<String> targeted = new HashSet<>();
        if (edges != null) {
            for (WorkflowEdge edge : edges) {
                String from = edge.sourceNodeID();
                String to = edge.targetNodeID();
                if (from == null || !chain.hasNode(from)) {
                    throw new NodeException("边起点不存在: " + from + " (链 " + chainId + ")", true);
                }
                if (to == null || !chain.hasNode(to)) {
                    throw new NodeException("边终点不存在: " + from + " -> " + to + " (链 " + chainId + ")", true);
                }

                String port;
                if (edge.sourcePortID() == null || edge.sourcePortID().isEmpty()) {
                    port = INodeContext.PORT_DEFAULT;
                } else {
                    port = edge.sourcePortID();
                }

                chain.connect(from, port, to);
                targeted.add(to);
            }
        }

        // 入口校验：唯一 start
        if (starts.isEmpty()) {
            if (topLevel) {
                throw new NodeException("规则链 " + chainId + " 缺少 start 入口节点", true);
            }
            // Loop 的 blocks 子图通常不含 start：以"无入边的节点"为入口，全有入边时退回首节点
            for (WorkflowNode node : nodes) {
                if (!targeted.contains(node.id())) {
                    chain.addEntry(node.id());
                }
            }
            if (chain.entryNodes().isEmpty() && !nodes.isEmpty()) {
                chain.addEntry(nodes.get(0).id());
            }
        } else if (starts.size() > 1) {
            throw new NodeException("规则链 " + chainId + " 只能有一个 start，实际: " + starts, true);
        } else {
            chain.addEntry(starts.get(0));
        }
        if (topLevel && endCount == 0) {
            throw new NodeException("规则链 " + chainId + " 至少需要一个 end 节点", true);
        }
        return chain;
    }

    private RuleNode resolveNode(String type) throws NodeException {
        RuleNode node = registry.get(type);
        if (node == null) {
            throw new NodeException("找不到类型为 " + type + " 的 RuleNode 实现", true);
        }
        return node;
    }

    private NodeConfig buildConfig(RuleChain chain, String nodeId, String type, WorkflowNode node)
            throws NodeException {
        Map<String, Object> data = new LinkedHashMap<>(node.data());
        if (WorkflowConstants.TYPE_LOOP.equals(type) && !node.blocks().isEmpty()) {
            String subChainId = nodeId + WorkflowConstants.BLOCKS_CHAIN_SUFFIX;
            chain.addSubChain(subChainId, build(subChainId, subChainId, node.blocks(), node.edges(), false));
            data.put(WorkflowConstants.BLOCKS_CHAIN_ID, subChainId);
        }
        return new NodeConfig(data);
    }

    /**
     * 便捷入口：从 JSON 字符串直接装载。
     */
    public RuleChain loadJson(String json) throws NodeException {
        return load(JsonUtil.parseObject(json));
    }
}
