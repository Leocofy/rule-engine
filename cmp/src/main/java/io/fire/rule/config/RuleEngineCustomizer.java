package io.fire.rule.config;

import io.fire.rule.RuleEngine;

/**
 * 规则引擎自定义配置扩展点。
 *
 * <p>业务模块实现此接口并注册为 Spring Bean，即可在引擎构建阶段
 * 注入 capability、自定义策略等，无需 starter 硬编码引用业务节点。
 *
 * <p>示例：
 * <pre>
 * &#64;Component
 * public class MyCustomizer implements RuleEngineCustomizer {
 *     &#64;Override
 *     public void customize(RuleEngine.Builder builder) {
 *         builder.capability(MyService.class, new MyServiceImpl());
 *     }
 * }
 * </pre>
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@FunctionalInterface
public interface RuleEngineCustomizer {

    void customize(RuleEngine.Builder builder);
}
