package io.fire.rule.example.stress;

import io.fire.rule.RuleEngine;
import io.fire.rule.config.RuleEngineCustomizer;
import io.fire.rule.runtime.dispatcher.KeyOrderedDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 把压测装配的调度器与异步线程池替换进引擎。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@Component
@ConditionalOnProperty(name = "example.stress.enabled", havingValue = "true")
public class StressRuleEngineCustomizer implements RuleEngineCustomizer {

    private final KeyOrderedDispatcher dispatcher;
    private final ThreadPoolExecutor asyncPool;

    public StressRuleEngineCustomizer(KeyOrderedDispatcher dispatcher, ThreadPoolExecutor asyncPool) {
        this.dispatcher = dispatcher;
        this.asyncPool = asyncPool;
    }

    @Override
    public void customize(RuleEngine.Builder builder) {
        builder.dispatcher(dispatcher).asyncPool(asyncPool);
    }
}
