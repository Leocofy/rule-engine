package io.fire.rule.config;

import io.fire.rule.spi.*;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationNotAllowedException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring 节点注册表：从 ApplicationContext 中收集所有 {@link RuleNode} bean，
 * 按 {@link NodeComponent#type()} 建立映射。
 *
 * <p>由 {@link RuleEngineAutoConfiguration} 自动创建，无需手动注册。
 * 业务侧只需把自定义节点声明为 Spring bean 即可被引擎发现。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
public class SpringNodeRegistry implements INodeRegistry, ApplicationContextAware {

    private final Map<String, RuleNode> ruleNodes = new HashMap<>(16);
    private final Map<String, NodeDefinition> definitions = new HashMap<>(16);

    @Override
    public RuleNode get(String type) {
        return ruleNodes.get(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String type, Class<T> requireClazz) throws NodeException {
        RuleNode bean = get(type);
        if (requireClazz != null && !requireClazz.isInstance(bean)) {
            throw new NodeException("Bean type mismatch: " + type + " expected: " + requireClazz.getName() + " actual: " + bean.getClass().getName());
        }
        return (T) bean;
    }

    @Override
    public NodeDefinition definition(String type) {
        return definitions.get(type);
    }

    @Override
    public Collection<NodeDefinition> definitions() {
        return definitions.values();
    }

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        Map<String, RuleNode> beans = context.getBeansOfType(RuleNode.class);
        beans.forEach((name, node) -> {
            NodeDefinition def;
            if (node instanceof RuleNodeDefinition) {
                def = new NodeDefinition((RuleNodeDefinition) node);
            } else {
                NodeComponent cmp = node.getClass().getAnnotation(NodeComponent.class);
                if (cmp == null) {
                    throw new BeanCreationNotAllowedException(name, "Missing @NodeComponent annotation");
                }
                def = NodeDefinition.builder()
                        .type(cmp.type())
                        .title(cmp.title())
                        .description(cmp.description())
                        .category(cmp.category())
                        .version(cmp.version())
                        .relations(cmp.relations())
                        .customRelations(cmp.customRelations())
                        .build();
            }
            if (ruleNodes.containsKey(def.getType())) {
                throw new BeanCreationNotAllowedException(name, "Duplicate node type: " + def.getType());
            }
            ruleNodes.put(def.getType(), node);
            definitions.put(def.getType(), def);
        });
    }
}
