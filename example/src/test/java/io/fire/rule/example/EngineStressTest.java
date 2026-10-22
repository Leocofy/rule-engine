package io.fire.rule.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.example.service.MockSinkHandler;
import io.fire.rule.example.source.CallBackSource;
import io.fire.rule.example.stress.StressEngineObserver;
import io.fire.rule.example.stress.StressMetricsController;
import io.fire.rule.example.support.TelemetrySimulator;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.runtime.Envelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 引擎白盒压测：绕过 HTTP，直接把复杂报文灌入 CallBackSource，测量引擎处理吞吐。
 *
 * <p>默认不运行，需显式开启：
 * <pre>
 *   mvn -pl example -am test -Dtest=EngineStressTest -DfailIfNoTests=false \
 *       -Dstress.test=true -Dstress.messages=2000 -Dstress.devices=64
 * </pre>
 *
 * <p>加 {@code -Dexample.ai.latency-ms=0} 可去掉 AI 节点的模拟耗时，
 * 用于区分「引擎本身开销」与「外部调用耗时」。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@SpringBootTest(properties = {
        "example.enabled=false",
        "example.stress.enabled=true",
        "rule.engine.auto-start=true",
        "rule.engine.poll-size=64",
        "rule.engine.pack-timeout-ms=20000"
})
@EnabledIfSystemProperty(named = "stress.test", matches = "true")
@Timeout(180)
class EngineStressTest {

    private static final int MESSAGES = Math.max(1, Integer.getInteger("stress.messages", 2000));
    private static final int DEVICES = Math.max(1, Integer.getInteger("stress.devices", 64));

    @Autowired
    private CallBackSource callBackSource;

    @Autowired
    private TelemetrySimulator simulator;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StressEngineObserver observer;

    @Autowired
    private StressMetricsController metricsController;

    @Autowired
    private MockSinkHandler sinkHandler;

    @Test
    void criticalPathThroughput() throws Exception {
        run("critical", MESSAGES, DEVICES);
    }

    @Test
    void normalPathThroughput() throws Exception {
        run("normal", MESSAGES, DEVICES);
    }

    @Test
    void statsEndpointExposesRuntimeState() {
        Map<String, Object> stats = metricsController.stats();
        assertTrue(stats.containsKey("ingress"));
        assertTrue(stats.containsKey("engine"));
        assertTrue(stats.containsKey("asyncPool"));
        assertTrue(stats.containsKey("lanes"));
        assertTrue(stats.containsKey("jvm"));
        assertTrue(((Map<?, ?>) stats.get("engine")).containsKey("tpsWindow"));
    }

    private void run(String mode, int total, int devices) throws Exception {
        drainIngress();
        observer.reset();
        sinkHandler.reset();

        long start = System.nanoTime();
        for (int i = 0; i < total; i++) {
            submit(mode, "stress-" + mode + "-" + (i % devices));
        }
        awaitCommitted(total, 150_000L);
        long elapsedNanos = System.nanoTime() - start;

        double seconds = elapsedNanos / 1_000_000_000d;
        double tps = total / seconds;
        System.out.printf("[STRESS] mode=%s messages=%d devices=%d elapsed=%.2fs tps=%.2f "
                        + "deadLetters=%d timeouts=%d failedPacks=%d%n",
                mode, total, devices, seconds, tps,
                observer.deadLetters(), observer.timeouts(), observer.snapshot().get("failedPacks"));

        assertEquals(0L, observer.deadLetters(), "压测期间不应出现死信");
        assertEquals(0L, observer.timeouts(), "压测期间不应出现批次超时");
        assertEquals((long) total, observer.messages(), "已提交消息数应与投递数一致");
        System.out.println(observer.nodeReport());
    }

    @SuppressWarnings("unchecked")
    private void submit(String mode, String deviceId) throws Exception {
        Map<String, Object> sample = simulator.nextSample();
        Map<String, Object> device = (Map<String, Object>) sample.get("device");
        device.put("deviceId", deviceId);
        ((Map<String, Object>) device.get("status")).put("online", true);

        Map<String, Object> telemetry = (Map<String, Object>) sample.get("telemetry");
        if ("critical".equals(mode)) {
            telemetry.put("temperature", 118.4);
            telemetry.put("smokeDensity", 1.12);
            telemetry.put("coLevel", 68.5);
        } else {
            telemetry.put("temperature", 32.6);
            telemetry.put("smokeDensity", 0.08);
            telemetry.put("coLevel", 4.2);
        }

        callBackSource.add(new Envelope(RuleMessage.newMessage()
                .type("DEVICE_TELEMETRY")
                .originator(deviceId)
                .payload(objectMapper.writeValueAsString(sample))
                .build()));
    }

    private void awaitCommitted(int total, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (observer.messages() >= total) {
                return;
            }
            Thread.sleep(50L);
        }
        fail("等待处理完成超时: messages=" + observer.messages() + "/" + total
                + ", deadLetters=" + observer.deadLetters()
                + ", timeouts=" + observer.timeouts());
    }

    private void drainIngress() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (callBackSource.size() == 0 && callBackSource.pendingBatches() == 0) {
                return;
            }
            Thread.sleep(20L);
        }
    }
}
