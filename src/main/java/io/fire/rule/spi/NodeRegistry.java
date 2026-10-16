package io.fire.rule.spi;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点注册中心：type -> 实现类。
 *
 * <p>支持两种注册方式：
 * <ol>
 *   <li>手工注册 {@link #register(Class)} —— 明确、便于测试</li>
 *   <li>SPI 自动发现 {@link #discover()} —— 通过 META-INF/services 让 jar 自带节点，
 *       新增节点库无需改引擎代码</li>
 * </ol>
 */
public final class NodeRegistry implements INodeRegistry {

    private final Map<String, Class<? extends RuleNode>> nodes = new ConcurrentHashMap<>();
    private final Map<String, NodeDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<Class<? extends RuleNode>, RuleNode> instances = new ConcurrentHashMap<>();

    public void register(Class<? extends RuleNode> nodeClass) throws NodeException {
        NodeDefinition def = nodeClass.getAnnotation(NodeDefinition.class);
        if (def == null) {
            throw new NodeException("节点缺少 @NodeDefinition 注解: " + nodeClass.getName(), true);
        }
        if (nodes.containsKey(def.type())) {
            throw new NodeException("节点类型重复注册: " + def.type(), true);
        }
        nodes.put(def.type(), nodeClass);
        definitions.put(def.type(), def);
    }

    /**
     * 通过 JDK SPI 扫描 classpath 下所有 RuleNode 实现。
     */
    public int discover() throws NodeException {
        int count = 0;
        ServiceLoader<RuleNode> loader = ServiceLoader.load(RuleNode.class);
        for (RuleNode node : loader) {
            Class<? extends RuleNode> clazz = node.getClass();
            NodeDefinition def = clazz.getAnnotation(NodeDefinition.class);
            if (def == null || nodes.containsKey(def.type())) {
                continue;
            }
            nodes.put(def.type(), clazz);
            definitions.put(def.type(), def);
            count++;
        }
        return count;
    }

    public RuleNode create(String type) throws NodeException {
        Class<? extends RuleNode> clazz = nodes.get(type);
        if (instances.containsKey(clazz)) {
            return instances.get(clazz);
        }
        if (clazz == null) {
            throw new NodeException("未注册的节点类型: " + type, true);
        }
        try {
            RuleNode instance = clazz.getDeclaredConstructor().newInstance();
            instances.put(clazz, instance);
            return instance;
        } catch (Exception e) {
            throw new NodeException("节点实例化失败: " + type, e);
        }
    }

    public NodeDefinition definition(String type) {
        return definitions.get(type);
    }

    public Set<String> types() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    /**
     * 导出给前端做节点面板，或用于配置校验。
     */
    public Collection<NodeDefinition> definitions() {
        return Collections.unmodifiableCollection(new ArrayList<NodeDefinition>(definitions.values()));
    }

    public List<String> declaredRelations(String type) {
        NodeDefinition def = definitions.get(type);
        if (def == null || def.relations().length == 0) {
            List<String> defaults = new ArrayList<String>();
            defaults.add(Relations.SUCCESS);
            defaults.add(Relations.FAILURE);
            return defaults;
        }
        List<String> rels = new ArrayList<String>();
        for (String r : def.relations()) {
            rels.add(r);
        }
        return rels;
    }

    @Override
    public RuleNode get(String type) throws NodeException {
        return create(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> clazz) throws NodeException {
        return (T) instances.get(clazz);
    }

    /**
     * 常用关系名常量。业务可自定义，如 "有隐患" / "需现场复核"。
     */
    public interface Relations {
        String SUCCESS = "Success";
        String FAILURE = "Failure";
        String TRUE = "True";
        String FALSE = "False";
    }

}
