package io.fire.rule.spring.base;

/**
 * 声明节点固定的 Config 类型，供 Loader 在构建 RuleChain 时预编译并缓存。
 */
public interface NodeConfigAware {

    Class<?> configType();

}