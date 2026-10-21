package io.fire.rule.config;

import io.fire.rule.spi.*;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Description: SpringNodeRegistry
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@Configuration
public class SpringNodeRegistry implements INodeRegistry, ApplicationContextAware {

    private final Map<String, RuleNode> ruleNodes = new HashMap<>(16);
    private final Map<String, NodeDefinition> definitions = new HashMap<>(16);

    @Override
    public RuleNode get(String type) throws NodeException {
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
        for (RuleNode node : beans.values()) {
            NodeComponent cmp = node.getClass().getAnnotation(NodeComponent.class);
            ruleNodes.put(cmp.type(), node);
            definitions.put(cmp.type(), new NodeDefinition(cmp));
        }
    }
}
