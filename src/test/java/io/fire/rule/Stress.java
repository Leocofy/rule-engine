package io.fire.rule;

import io.fire.rule.model.*;
import io.fire.rule.nodes.*;
import io.fire.rule.runtime.*;
import io.fire.rule.runtime.dispatcher.KeyOrderedDispatcher;
import io.fire.rule.spi.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 压测：多设备并发 + 串行保序 + 批次处理 */
public class Stress {
    public static void main(String[] a) throws Exception {
        final ConcurrentHashMap<String, List<Integer>> perDev = new ConcurrentHashMap<>();
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();

        NodeRegistry reg = new NodeRegistry();
        reg.register(StressNode.class);
        reg.register(FilterNode.class);

        int lanes = 16;
        RuleEngine e = RuleEngine.builder()
                .registry(reg)
                .expressionEngine(new SimpleIExpressionEngine())
                .source(new InMemoryMessageSource("q"))
                .dispatcher(new KeyOrderedDispatcher(lanes, 4096))
                .submitStrategy(new BurstSubmitStrategy())
                .retryStrategy(RetryStrategy.retryFailed().maxRetries(2))
                .packTimeoutMs(5000)
                .defaultChainId("c")
                .capability(StressNode.Sink.class, new StressNode.Sink() {
                    public void accept(String dev, int seq) {
                        processed.incrementAndGet();
                        List<Integer> l = perDev.get(dev);
                        if (l == null) { l = Collections.synchronizedList(new ArrayList<>()); perDev.put(dev, l); }
                        l.add(seq);
                    }
                }).build();
        e.loadChain("{\"id\":\"c\",\"nodes\":{\"s\":{\"type\":\"stress\"}},\"entry\":[\"s\"]}");
        e.start();

        // 20 台设备 x 每台 200 条 = 4000 条
        int devs = 20, per = 200;
        long t0 = System.currentTimeMillis();
        for (int s = 0; s < per; s++) {
            for (int d = 0; d < devs; d++) {
                e.submitSync(RuleMessage.newMessage().type("T").originator("dev" + d)
                        .payload("seq=" + s).build());
            }
        }
        long cost = System.currentTimeMillis() - t0;

        Thread.sleep(500);
        e.stop();

        // 校验每台设备内部严格有序
        int bad = 0;
        for (List<Integer> l : perDev.values()) {
            for (int i = 1; i < l.size(); i++) {
                if (l.get(i) <= l.get(i - 1)) { bad++; break; }
            }
        }
        System.out.println("总消息: " + (devs * per));
        System.out.println("已处理: " + processed.get());
        System.out.println("耗时: " + cost + "ms  吞吐: " + (devs * per * 1000L / Math.max(1, cost)) + " msg/s");
        System.out.println("设备数: " + perDev.size() + "  顺序异常设备: " + bad);
        System.out.println(bad == 0 && processed.get() == devs * per ? "压测通过" : "压测失败");
    }
}
