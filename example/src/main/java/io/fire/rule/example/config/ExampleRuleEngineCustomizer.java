package io.fire.rule.example.config;

import io.fire.rule.RuleEngine;
import io.fire.rule.config.RuleEngineCustomizer;
import io.fire.rule.example.node.AiDecisionNode;
import io.fire.rule.example.node.AsyncSinkNode;
import io.fire.rule.example.service.MockDecisionService;
import io.fire.rule.example.service.MockSinkHandler;
import org.springframework.stereotype.Component;

/**
 * Example 规则引擎自定义配置：注入业务 capability。
 *
 * <p>将 {@link MockDecisionService} 和 {@link MockSinkHandler} 注入引擎，
 * 使 {@link AiDecisionNode} 和 {@link AsyncSinkNode} 能获取到业务服务。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@Component
public class ExampleRuleEngineCustomizer implements RuleEngineCustomizer {

    private final MockDecisionService decisionService;
    private final MockSinkHandler sinkHandler;

    public ExampleRuleEngineCustomizer(MockDecisionService decisionService, MockSinkHandler sinkHandler) {
        this.decisionService = decisionService;
        this.sinkHandler = sinkHandler;
    }

    @Override
    public void customize(RuleEngine.Builder builder) {
        builder.capability(AiDecisionNode.DecisionService.class, decisionService)
                .capability(AsyncSinkNode.SinkHandler.class, sinkHandler);
    }
}
