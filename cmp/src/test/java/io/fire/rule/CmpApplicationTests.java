package io.fire.rule;

import com.googlecode.aviator.AviatorEvaluator;
import io.fire.rule.config.SpringNodeRegistry;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.source.PullSource;
import io.fire.rule.spi.IExpressionEngine;
import io.fire.rule.spi.NodeRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import javax.annotation.Resource;
import java.util.Set;

@SpringBootTest
class CmpApplicationTests {

    @Resource
    private SpringNodeRegistry springNodeRegistry;

    @Resource
    private PullSource pullSource;

    @Test
    void contextLoads() {
        RuleEngine.builder()
                .registry(NodeRegistry.builder().registry(springNodeRegistry).build())
                .source(pullSource)
                .expressionEngine(new AviatorExpressionEngine())
    }

    public static class AviatorExpressionEngine implements IExpressionEngine {
        @Override
        public boolean evaluate(String expr, RuleMessage msg) {
//            return AviatorEvaluator.execute(expr, )
            return false;
        }

        @Override
        public String transform(String expr, RuleMessage msg) {
            return "";
        }

        @Override
        public Set<String> route(String expr, RuleMessage msg) {
            return Set.of();
        }
    }

}
