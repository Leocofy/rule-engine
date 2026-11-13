package io.fire.rule.core.spi;

import io.fire.rule.core.runtime.TaskExecuteException;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点注册中心：type -> 实现类。
 *
 * <p>支持两种注册方式：
 * <ol>
 *   <li>SPI 自动发现 {@link #discover()} —— 通过 META-INF/services 让 jar 自带节点，
 *       新增节点库无需改引擎代码</li>
 * </ol>
 */
public final class NodeRegistry implements INodeRegistry {

    /**
     * 一级缓存，节点类型 -> 节点
     */
    private final Map<String, RuleNode> NODES = new ConcurrentHashMap<>(12);

    public NodeRegistry() {
        discover();
    }

    public <T extends RuleNode & IRuleDefine> void register(T node) throws NodeException {
        if (NODES.containsKey(node.type())) {
            throw new NodeException("节点类型重复注册: " + node.type(), true);
        }
        NODES.put(node.type(), node);
    }

    /**
     * 通过 JDK SPI 扫描 classpath 下所有 RuleNode 实现。
     */
    private int discover() {
        try {
            int count = 0;
            ServiceLoader<RuleNode> loader = ServiceLoader.load(RuleNode.class);
            for (RuleNode node : loader) {
                if (node.getClass().isInstance(IRuleDefine.class)) {
                    register((RuleNode & IRuleDefine) node);
                } else {
                    throw new NodeException("节点必须实现 IRuleDefine 接口: " + node.getClass().getName(), true);
                }
                count++;
            }
            return count;
        } catch (Exception e) {
            throw new TaskExecuteException("Failed to discover nodes", e);
        }
    }

    private RuleNode getNode(String type) throws NodeException {
        RuleNode node = NODES.get(type);
        if (node == null) {
            throw new NodeException("未注册的节点类型: " + type, true);
        }
        return node;
    }

    @Override
    public RuleNode get(String type) throws NodeException {
        return getNode(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String type, Class<T> requireClazz) throws NodeException {
        RuleNode node = getNode(type);
        if (requireClazz.isInstance(node)) {
            return (T) node;
        }
        throw new NodeException("Node type mismatch: " + type + " expected: " + requireClazz.getName() + " actual: " + node.getClass().getName(), true);
    }

}
