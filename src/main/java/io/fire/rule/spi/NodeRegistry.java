package io.fire.rule.spi;

import java.util.*;
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

    private static final INodeRegistry NOOP = new INodeRegistry() {
        @Override
        public NodeDefinition definition(String type) {
            return null;
        }

        @Override
        public Collection<NodeDefinition> definitions() {
            return Collections.emptySet();
        }

        @Override
        public RuleNode get(String type) {
            return null;
        }

        @Override
        public <T> T get(String type, Class<T> requireClazz) {
            return null;
        }
    };

    private final Map<String, NodeDefinition> DEFINITIONS = new ConcurrentHashMap<>(12);

    /**
     * 一级缓存，节点类型 -> 节点
     */
    private final Map<String, RuleNode> NODES = new ConcurrentHashMap<>(12);

    /**
     * 用于 spring 集成，将 spi 注册的节点也纳入 spring 管理
     */
    private final INodeRegistry registry;

    private NodeRegistry(Builder builder) throws NodeException {
        this.registry = builder.registry;

        if (builder.autoDiscover) {
            discover();
        }

        for (RuleNode node : builder.nodes.values()) {
            register(node);
        }
    }

    private void register(RuleNode node) throws NodeException {
        Class<? extends RuleNode> clazz = node.getClass();
        NodeComponent def = clazz.getAnnotation(NodeComponent.class);
        if (def == null) {
            throw new NodeException("节点缺少 @NodeDefinition 注解: " + clazz.getName(), true);
        }
        if (NODES.containsKey(def.type())) {
            throw new NodeException("节点类型重复注册: " + def.type(), true);
        }
        NODES.put(def.type(), node);
        DEFINITIONS.put(def.type(), new NodeDefinition(def));
    }

    /**
     * 通过 JDK SPI 扫描 classpath 下所有 RuleNode 实现。
     */
    private int discover() throws NodeException {
        int count = 0;
        ServiceLoader<RuleNode> loader = ServiceLoader.load(RuleNode.class);
        for (RuleNode node : loader) {
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
        NodeDefinition definition = registry.definition(type);
        if (definition == null) {
            definition = DEFINITIONS.get(type);
        }
        return definition;
    }

    /**
     * 导出给前端做节点面板，或用于配置校验。
     */
    @Override
    public Collection<NodeDefinition> definitions() {
        Collection<NodeDefinition> def = registry.definitions();
        List<NodeDefinition> definitions = new ArrayList<>(def.size() + DEFINITIONS.size());
        definitions.addAll(def);
        definitions.addAll(DEFINITIONS.values());
        return Collections.unmodifiableCollection(definitions);
    }

    @Override
    public RuleNode get(String type) throws NodeException {
        RuleNode ruleNode = registry.get(type);
        if (ruleNode != null)
            return ruleNode;
        else return getNode(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String type, Class<T> requireClazz) throws NodeException {
        T node = registry.get(type, requireClazz);
        if (node != null) return node;
        return (T) getNode(type);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static NodeRegistry defaultRegistry() {
        try {
            return builder().autoDiscover(true).build();
        } catch (NodeException e) {
            throw new RuntimeException(e);
        }
    }

    public static class Builder {
        private INodeRegistry registry = NOOP;
        private final Map<Class<? extends RuleNode>, RuleNode> nodes = new ConcurrentHashMap<>();
        private boolean autoDiscover = false;

        public Builder autoDiscover(boolean autoDiscover) {
            this.autoDiscover = autoDiscover;
            return this;
        }

        public Builder register(RuleNode node) {
            this.nodes.put(node.getClass(), node);
            return this;
        }

        public Builder register(Class<? extends RuleNode> nodeClazz, Object... args) throws NodeException {
            try {
                RuleNode node;
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
                throw new NodeException("Failed to instantiate " + nodeClazz, e);
            }
            return this;
        }

        public Builder registry(INodeRegistry registry) {
            this.registry = registry;
            return this;
        }

        public NodeRegistry build() throws NodeException {
            return new NodeRegistry(this);
        }
    }

}
