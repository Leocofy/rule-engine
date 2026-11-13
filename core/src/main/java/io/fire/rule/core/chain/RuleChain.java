package io.fire.rule.core.chain;

import io.fire.rule.core.model.NodeConfig;
import io.fire.rule.core.spi.RuleNode;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则链：节点 + 端口（port）构成的有向图（链 DSL 的运行期内存表示）。
 *
 * <p><b>端口取代旧的"关系名"</b>。
 * 节点不写 {@code if (x) 调A else 调B}，而是喊一句"这条消息从端口 X 出去"，
 * 由引擎查邻接表决定发给谁。这样编排可配置化，节点与流程彻底解耦。
 *
 * <p>邻接表结构：{@code (nodeId, port) -> 下游节点列表}
 *
 * <p>内联子图（如循环体子链）作为独立的 {@link #subChains} 随主链一起装载，
 * 由链存储登记后供执行器按 ID 取用。
 */
public final class RuleChain {

    private String id;
    private String name;
    private final Map<String, RuleNode> nodes = new LinkedHashMap<>();
    private final Map<String, String> nodeTypes = new LinkedHashMap<>();
    private final Map<String, NodeConfig> nodeConfigs = new HashMap<>();

    /**
     * 链级预编译缓存：nodeId -> 已绑定 Config 对象（未解析 IFlowValue）。
     * 随 RuleChain 生命周期回收，避免公共 binder 缓存跨链累积。
     */
    private final Map<String, Object> compiledConfigs = new ConcurrentHashMap<>();

    /**
     * fromNodeId -> (port -> 下游节点列表)
     */
    private final Map<String, Map<String, List<String>>> adjacency = new HashMap<>();

    private final List<String> entryNodes = new ArrayList<>();

    /**
     * 内联子链（如循环体）：subChainId -> 子链。随主链装载，注册后由存储提升到顶层可查。
     */
    private final Map<String, RuleChain> subChains = new LinkedHashMap<>();

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

    public void addNode(String nodeId, String type, RuleNode node, NodeConfig config) {
        nodes.put(nodeId, node);
        nodeTypes.put(nodeId, type);
        nodeConfigs.put(nodeId, config != null ? config : NodeConfig.EMPTY);
    }

    public NodeConfig nodeConfig(String nodeId) {
        return nodeConfigs.getOrDefault(nodeId, NodeConfig.EMPTY);
    }

    /**
     * Loader 预编译结果写入；nodeId 在当前链内唯一。
     */
    public void putCompiledConfig(String nodeId, Object config) {
        if (nodeId != null && config != null) {
            compiledConfigs.put(nodeId, config);
        }
    }

    /**
     * 运行期取预编译 Config；类型不匹配或未预编译返回 null，由调用方 fallback。
     */
    public <T> T compiledConfig(String nodeId, Class<T> type) {
        Object config = compiledConfigs.get(nodeId);
        return type.isInstance(config) ? type.cast(config) : null;
    }

    /**
     * 声明一条带端口的边。同一端口可连多个下游，天然支持 fan-out。
     */
    public void connect(String fromNodeId, String port, String toNodeId) {
        adjacency.computeIfAbsent(fromNodeId, k -> new HashMap<>())
                .computeIfAbsent(port, k -> new ArrayList<>())
                .add(toNodeId);
    }

    public void addEntry(String nodeId) {
        entryNodes.add(nodeId);
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

    public void setId(String id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * 查邻接表：找不到下游返回空列表。
     */
    public List<String> downstream(String nodeId, String port) {
        if (adjacency.containsKey(nodeId)) {
            List<String> targets = adjacency.get(nodeId).get(port);
            if (targets != null) {
                return Collections.unmodifiableList(targets);
            }
        }
        return Collections.emptyList();
    }

    /**
     * 当前节点的全部出边端口及对应下游，端口 -> 下游节点列表。
     */
    public Map<String, List<String>> outgoing(String nodeId) {
        Map<String, List<String>> ports = adjacency.get(nodeId);
        if (ports == null || ports.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : ports.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 挂载一条内联子链（供循环体等子图使用）。
     */
    public void addSubChain(String subChainId, RuleChain subChain) {
        subChains.put(subChainId, subChain);
    }

    public Map<String, RuleChain> subChains() {
        return Collections.unmodifiableMap(subChains);
    }
}
