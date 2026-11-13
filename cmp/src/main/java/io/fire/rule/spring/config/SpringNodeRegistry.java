package io.fire.rule.spring.config;

import io.fire.rule.spring.annotation.NodeComponent;
import io.fire.rule.spring.model.NodeDefinition;
import io.fire.rule.core.spi.INodeRegistry;
import io.fire.rule.core.spi.NodeException;
import io.fire.rule.core.spi.RuleNode;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring 节点注册表：从 ApplicationContext 中收集所有 {@link RuleNode} bean，
 *
 * <p>由 {@link RuleEngineAutoConfiguration} 自动创建，无需手动注册。
 * 业务侧只需把自定义节点声明为 Spring bean 即可被引擎发现。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
public class SpringNodeRegistry implements INodeRegistry, SmartInitializingSingleton, BeanFactoryAware {

    /**
     * 元信息：给管理后台 / 编排 UI 用，不在执行热点上
     */
    private final Map<String, NodeDefinition> definitions = new LinkedHashMap<>(24);

    /**
     * 元信息：给管理后台 / 编排 UI 用，不在执行热点上
     */
    private final Map<String, RuleNode> nodes = new LinkedHashMap<>(24);

    private ConfigurableListableBeanFactory beanFactory;
    private volatile boolean loaded;

    @Override
    public RuleNode get(String type) {
        return get(type, RuleNode.class);
    }

    @Override
    public <T> T get(String type, Class<T> requireClazz) {
        ensureLoaded();
        RuleNode node = nodes.get(type);
        if (node == null) {
            throw new NodeException("Unknown node type: " + type);
        }
        if (!requireClazz.isInstance(node)) {
            throw new NodeException("Node [" + type + "] is not a " + requireClazz.getName());
        }
        return requireClazz.cast(node);
    }

    public NodeDefinition definition(String type) {
        ensureLoaded();
        return definitions.get(type);
    }

    public Collection<NodeDefinition> definitions() {
        ensureLoaded();
        return Collections.unmodifiableCollection(definitions.values());
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ensureLoaded();
    }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (SpringNodeRegistry.class) {
            if (loaded) return;
            doLoad();
            loaded = true;
        }
    }

    private void doLoad() {
        // includeNonSingletons=true 覆盖 prototype；allowEagerInit=false 不提前触发 FactoryBean
        for (String beanName : beanFactory.getBeanNamesForType(RuleNode.class, true, false)) {
            NodeComponent ann = findNodeComponent(beanName);
            if (ann == null) {
                throw new NodeException("Missing @NodeComponent on bean: " + beanName);
            }
            String type = ann.type();
            if (!StringUtils.hasText(type)) {
                throw new NodeException("@NodeComponent.type() is empty on bean: " + beanName);
            }
            definitions.put(type, createNodeDefinition(ann));
            nodes.put(type, beanFactory.getBean(beanName, RuleNode.class));
        }
    }

    /**
     * 三级降级：BeanDefinition 元数据 → @Bean 工厂方法 → 真实目标类
     */
    private NodeComponent findNodeComponent(String beanName) {
        BeanDefinition bd = null;
        try {
            bd = beanFactory.getBeanDefinition(beanName);
        } catch (NoSuchBeanDefinitionException ignored) {
            // 手动 registerSingleton 进来的没有 BeanDefinition，走兜底
        }

        // 类上标注：不实例化 Bean、不受 JDK/CGLIB 代理影响、支持组合注解
        NodeComponent ann = NodeComponentRegistrar.mergedFrom(bd);
        if (ann != null) {
            return ann;
        }

        // 真实目标类（含父类、接口上的元注解），getUserClass 剥掉 CGLIB 壳
        Class<?> type = beanFactory.getType(beanName);
        if (type != null) {
            return AnnotatedElementUtils.findMergedAnnotation(ClassUtils.getUserClass(type), NodeComponent.class);
        }
        return null;
    }

    private NodeDefinition createNodeDefinition(NodeComponent ann) {
        NodeDefinition def = new NodeDefinition();
        def.setType(ann.type());
        def.setTitle(ann.name());
        def.setDescription(ann.description());
        def.setCategory(ann.category());
        def.setOrder(ann.order());
        def.setVersion(ann.version());
        def.setFlowNodeType(NodeDefinition.FLOW_BLOCK);
        return def;
    }
}
