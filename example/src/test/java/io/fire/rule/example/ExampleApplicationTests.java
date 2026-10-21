package io.fire.rule.example;

import io.fire.rule.RuleEngine;
import io.fire.rule.config.SpringNodeRegistry;
import io.fire.rule.spi.IExpressionEngine;
import io.fire.rule.runtime.MessageSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Starter 基础集成测试：验证 Spring 上下文加载、核心 Bean 装配、规则链自动加载。
 *
 * <p>加载完整上下文（含 ExampleConfig / PullSource）。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@SpringBootTest
class ExampleApplicationTests {

    @Autowired
    private RuleEngine ruleEngine;

    @Autowired
    private SpringNodeRegistry springNodeRegistry;

    @Autowired
    private IExpressionEngine expressionEngine;

    @Autowired
    private MessageSource messageSource;

    @Test
    void contextLoads() {
        assertNotNull(ruleEngine, "RuleEngine Bean 应被自动装配");
        assertNotNull(springNodeRegistry, "SpringNodeRegistry Bean 应被自动装配");
        assertNotNull(expressionEngine, "IExpressionEngine Bean 应被自动装配");
        assertNotNull(messageSource, "MessageSource Bean 应被自动装配");
    }

    @Test
    void chainAutoLoaded() {
        assertNotNull(ruleEngine.chainExecutor().chain("fireAlarm"),
                "规则链 fireAlarm 应被自动加载");
    }

    @Test
    void registryHasBuiltInNodes() {
        assertNotNull(ruleEngine.registry().definition("log"), "内置 log 节点应已注册");
        assertNotNull(ruleEngine.registry().definition("filter"), "内置 filter 节点应已注册");
        assertNotNull(ruleEngine.registry().definition("transform"), "内置 transform 节点应已注册");
    }
}
