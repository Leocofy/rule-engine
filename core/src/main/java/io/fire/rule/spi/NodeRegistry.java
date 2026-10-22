package io.fire.rule.spi;

import io.fire.rule.runtime.TaskExecuteException;

import java.util.Collection;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
    private final Map<String, RuleNodeDefinition> NODES = new ConcurrentHashMap<>(12);

    private NodeRegistry(Builder builder) throws NodeException {
        if (builder.autoDiscover) {
            discover();
        }

        for (RuleNodeDefinition node : builder.nodes.values()) {
            register(node);
        }
    }

    private void register(RuleNodeDefinition node) throws NodeException {
        if (NODES.containsKey(node.type())) {
            throw new NodeException("节点类型重复注册: " + node.type(), true);
        }
        NODES.put(node.type(), node);
    }

    /**
     * 通过 JDK SPI 扫描 classpath 下所有 RuleNode 实现。
     */
    private int discover() throws NodeException {
        int count = 0;
        ServiceLoader<RuleNodeDefinition> loader = ServiceLoader.load(RuleNodeDefinition.class);
        for (RuleNodeDefinition node : loader) {
            register(node);
            count++;
        }
        return count;
    }

    private RuleNode getNode(String type) throws NodeException {
        RuleNode node = NODES.get(type);
        if (node == null) {
            throw new NodeException("未注册的节点类型: " + type, true);
        }
        return node;
    }

    @Override
    public NodeDefinition definition(String type) {
        return NODES.containsKey(type) ? new NodeDefinition(NODES.get(type)) : null;
    }

    /**
     * 导出给前端做节点面板，或用于配置校验。
     */
    @Override
    public Collection<NodeDefinition> definitions() {
        return NODES.values().stream().map(NodeDefinition::new).collect(Collectors.toList());
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

    public static Builder builder() {
        return new Builder();
    }

    public static NodeRegistry defaultRegistry() {
        return builder().autoDiscover(true).build();
    }

    public static class Builder {
        private final Map<Class<? extends RuleNodeDefinition>, RuleNodeDefinition> nodes = new ConcurrentHashMap<>();
        private boolean autoDiscover = false;

        public Builder autoDiscover(boolean autoDiscover) {
            this.autoDiscover = autoDiscover;
            return this;
        }

        public Builder register(RuleNodeDefinition node) {
            this.nodes.put(node.getClass(), node);
            return this;
        }

        public Builder register(Class<? extends RuleNodeDefinition> nodeClazz, Object... args) throws TaskExecuteException {
            try {
                RuleNodeDefinition node;
                if (args == null || args.length == 0) {
                    node = nodeClazz.getDeclaredConstructor().newInstance();
                } else {
                    Class<?>[] argTypes = new Class<?>[args.length];
                    for (int i = 0; i < args.length; i++) {
                        argTypes[i] = args[i].getClass();
                    }
                    node = nodeClazz.getDeclaredConstructor(argTypes).newInstance(args);
                }

                this.nodes.put(node.getClass(), node);
            } catch (Exception e) {
                throw new TaskExecuteException("Failed to instantiate " + nodeClazz, e);
            }
            return this;
        }

        public NodeRegistry build() {
            try {
                return new NodeRegistry(this);
            } catch (NodeException e) {
                throw new TaskExecuteException("Failed to build NodeRegistry", e);
            }
        }
    }

}
