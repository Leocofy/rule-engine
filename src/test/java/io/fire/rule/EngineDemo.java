package io.fire.rule;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.nodes.*;
import io.fire.rule.nodes.SimpleExpressionEngine;
import io.fire.rule.observability.EngineObserver;
import io.fire.rule.runtime.InMemoryMessageSource;
import io.fire.rule.runtime.strategy.SequentialByKeySubmitStrategy;
import io.fire.rule.spi.NodeRegistry;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 可运行演示：加载 JSON 规则链 → 投递设备消息 → 观察分流结果。
 *
 * <p>运行方式（编译后）：java io.fire.rule.EngineDemo
 */
public class EngineDemo {

    public static void main(String[] args) throws Exception {
        // 1. 注册节点库
        NodeRegistry registry = new NodeRegistry();
        registry.register(LogNode.class);
        registry.register(FilterNode.class);
        registry.register(TransformNode.class);
        registry.register(AiDecisionNode.class);
        registry.register(AsyncSinkNode.class);

        // 2. 消息源（生产环境替换为 Kafka / RabbitMQ 实现）
        InMemoryMessageSource source = new InMemoryMessageSource("main");

        // 3. 统计观察器
        EngineObserver.Stats stats = new EngineObserver.Stats();

        // 4. 构建引擎：按设备串行，保证同一设备的消息有序
        RuleEngine engine = RuleEngine.builder()
                .registry(registry)
                .expressionEngine(new SimpleExpressionEngine())
                .source(source)
                .observer(stats)
                .submitStrategy(new SequentialByKeySubmitStrategy())
                .packTimeoutMs(3000)
                .capability(AiDecisionNode.DecisionService.class, new MockDecisionService())
                .capability(AsyncSinkNode.SinkHandler.class, new MockSinkHandler())
                .build();

        engine.loadChain(readResource("fire-alarm-chain.json"));
        engine.start();

        // 5. 投递消息：同一设备 3 条（应串行有序），另一设备 1 条（可并行）
        for (int i = 0; i < 3; i++) {
            source.offer(RuleMessage.newMessage()
                    .type("TELEMETRY")
                    .originator("device-A")
                    .payload("{\"temp\":\"" + (70 + i * 10) + "\",\"smoke\":\"0\"}")
                    .build());
        }
        source.offer(RuleMessage.newMessage()
                .type("TELEMETRY")
                .originator("device-B")
                .payload("{\"temp\":\"95\",\"smoke\":\"1\"}")
                .build());

        Thread.sleep(2000);

        System.out.println("\n===== 节点耗时统计 =====");
        System.out.println(stats.report());

        engine.stop();
    }

    /** 模拟 AI 判定：按温度给出中文结论，结论直接作为关系名。 */
    static class MockDecisionService implements AiDecisionNode.DecisionService {
        @Override
        public String decide(String agent, RuleMessage msg) {
            String payload = msg.getPayload();
            if (payload != null && payload.contains("95")) {
                return "有隐患";
            }
            return "无隐患";
        }
    }

    static class MockSinkHandler implements AsyncSinkNode.SinkHandler {
        @Override
        public void handle(RuleMessage msg) {
            System.out.println("  -> 已落库: " + msg.getPayload());
        }
    }

    private static String readResource(String name) throws Exception {
        InputStream in = EngineDemo.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("找不到资源: " + name);
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        return sb.toString();
    }
}
