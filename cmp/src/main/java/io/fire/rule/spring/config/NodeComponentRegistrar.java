package io.fire.rule.spring.config;

import io.fire.rule.spring.annotation.NodeComponent;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Constructor;
import java.util.List;

public class NodeComponentRegistrar implements ImportBeanDefinitionRegistrar, BeanFactoryAware {

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata meta, BeanDefinitionRegistry registry) {
        if (!AutoConfigurationPackages.has(beanFactory)) {
            return;
        }
        List<String> packages = AutoConfigurationPackages.get(beanFactory);
        new NodeComponentScanner(registry, beanFactory)
                .scan(packages.toArray(new String[0]));
    }

    static class NodeComponentScanner extends ClassPathBeanDefinitionScanner {

        NodeComponentScanner(BeanDefinitionRegistry registry, BeanFactory beanFactory) {
            super(registry, false);
            addIncludeFilter(new AnnotationTypeFilter(NodeComponent.class));
            addExcludeFilter(createTypeExcludeFilter(beanFactory));
        }

        @Override
        protected boolean isCandidateComponent(AnnotatedBeanDefinition bd) {
            return bd.getMetadata().isIndependent() && bd.getMetadata().isConcrete();
        }

        @Override
        protected boolean checkCandidate(String beanName, BeanDefinition beanDefinition) {
            try {
                return super.checkCandidate(beanName, beanDefinition);
            } catch (IllegalStateException ex) {
                throw new IllegalStateException("Duplicate @NodeComponent type: " + beanName, ex);
            }
        }
    }

    static TypeExcludeFilter createTypeExcludeFilter(BeanFactory beanFactory) {
        try {
            Constructor<TypeExcludeFilter> ctor = TypeExcludeFilter.class.getConstructor(BeanFactory.class);
            return ctor.newInstance(beanFactory);
        } catch (NoSuchMethodException ignored) {
            // Boot <= 2.1，走下面
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot create TypeExcludeFilter", ex);
        }
        try {
            TypeExcludeFilter filter = TypeExcludeFilter.class.getDeclaredConstructor().newInstance();
            filter.setBeanFactory(beanFactory);
            return filter;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot create TypeExcludeFilter", ex);
        }
    }

    /**
     * 从 BeanDefinition 元数据读注解：不实例化 Bean、不受代理影响、支持组合注解
     */
    static NodeComponent mergedFrom(BeanDefinition definition) {
        if (definition instanceof AnnotatedBeanDefinition) {
            MergedAnnotation<NodeComponent> m =
                    ((AnnotatedBeanDefinition) definition).getMetadata().getAnnotations().get(NodeComponent.class);
            if (m.isPresent()) {
                // synthesize() 会自动补上注解的 default 值
                return m.synthesize();
            }
        }
        return null;
    }
}