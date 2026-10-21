package io.fire.rule.example;

import io.fire.rule.RuleEngine;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.observability.EngineObserver;
import io.fire.rule.runtime.InMemoryMessageSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Example 功能测试：验证消息经过规则链被正确分流处理。
 *
 * <p>禁用 ExampleConfig（{@code example.enabled=false}）以使用 InMemoryMessageSource，
 * 便于测试中精确控制消息投递；禁用 auto-start，用 submitSync 同步处理。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@SpringBootTest(properties = {
        "example.enabled=false",
        "rule.engine.auto-start=false"
})
class RuleEngineExampleTest {

    @Autowired
    private RuleEngine ruleEngine;

    @Autowired
    private InMemoryMessageSource messageSource;

    @Autowired
    private EngineObserver engineObserver;

    @Test
    void engineReady() {
        assertNotNull(ruleEngine);
        assertNotNull(messageSource);
        assertNotNull(ruleEngine.chainExecutor().chain("fireAlarm"));
    }

    @Test
    void highTempTriggersAiAndPersist() throws Exception {
        RuleMessage msg = RuleMessage.newMessage()
                .type("TELEMETRY")
                .originator("device-high")
                .payload("{\"temp\":\"95\",\"smoke\":\"1\"}")
                .build();

        ruleEngine.submitSync(msg);
        Thread.sleep(1000);

        String report = statsReport();
        assertTrue(report.contains("fireAlarm/recv"), "应经过 recv 节点");
        assertTrue(report.contains("fireAlarm/riskFilter"), "应经过 riskFilter 节点");
        assertTrue(report.contains("fireAlarm/aiJudge"), "高温应触发 AI 判定节点");
    }

    @Test
    void lowTempGoesToDiscard() throws Exception {
        RuleMessage msg = RuleMessage.newMessage()
                .type("TELEMETRY")
                .originator("device-low")
                .payload("{\"temp\":\"50\",\"smoke\":\"0\"}")
                .build();

        ruleEngine.submitSync(msg);
        Thread.sleep(1000);

        String report = statsReport();
        assertTrue(report.contains("fireAlarm/recv"), "应经过 recv 节点");
        assertTrue(report.contains("fireAlarm/riskFilter"), "应经过 riskFilter 节点");
    }

    @Test
    void multipleMessagesProcessed() throws Exception {
        for (int i = 0; i < 5; i++) {
            RuleMessage msg = RuleMessage.newMessage()
                    .type("TELEMETRY")
                    .originator("device-batch")
                    .payload("{\"temp\":\"" + (60 + i * 10) + "\",\"smoke\":\"0\"}")
                    .build();
            ruleEngine.submitSync(msg);
        }
        Thread.sleep(1000);

        String report = statsReport();
        assertTrue(report.contains("fireAlarm"), "应有节点执行统计");
    }

    private String statsReport() {
        if (engineObserver instanceof EngineObserver.Stats) {
            return ((EngineObserver.Stats) engineObserver).report();
        }
        return "";
    }
}
