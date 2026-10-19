package io.fire.rule;

import io.fire.rule.model.*;
import io.fire.rule.nodes.*;
import io.fire.rule.runtime.*;
import io.fire.rule.runtime.strategy.SequentialByKeySubmitStrategy;
import io.fire.rule.spi.*;
import java.util.*;

/** 边界验证：串行顺序 / 失败重试 / 批次超时 / 嵌套链 / fan-out */
public class Verify {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + " -> " + detail); }
    }

    public static void main(String[] a) throws Exception {
        testSerialOrder();
        testFanOut();
        testNestedChain();
        testFailureRetry();
        testPackTimeout();
        testNodeStateless();
        System.out.println("\n===== 结果: pass=" + pass + ", fail=" + fail + " =====");
        if (fail > 0) System.exit(1);
    }

    // 1. 同一设备严格串行，不同设备并行
    static void testSerialOrder() throws Exception {
        System.out.println("\n[1] 串行顺序");
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        NodeRegistry reg = new NodeRegistry();
        reg.register(RecorderNode.class);
        RuleEngine e = RuleEngine.builder().registry(reg)
                .expressionEngine(new SimpleExpressionEngine())
                .source(new InMemoryMessageSource("q"))
                .submitStrategy(new SequentialByKeySubmitStrategy())
                .packTimeoutMs(5000)
                .capability(RecorderNode.Sink.class, new RecorderNode.Sink() {
                    public void accept(String s) { order.add(s); }
                }).build();
        e.loadChain("{\"id\":\"c\",\"name\":\"c\",\"nodes\":{\"r\":{\"type\":\"recorder\",\"props\":{\"tag\":\"A\"}}},\"entry\":[\"r\"]}");
        for (int i = 0; i < 5; i++) {
            e.submitSync(RuleMessage.newMessage().type("T").originator("dev-A")
                    .payload("seq=" + i).build());
        }
        e.stop();
        List<String> aSeq = new ArrayList<>();
        for (String s : order) if (s.startsWith("A")) aSeq.add(s);
        check("同设备 5 条按序", aSeq.toString().equals("[A0, A1, A2, A3, A4]"), aSeq.toString());
    }

    // 2. fan-out：一条消息发散到多个下游
    static void testFanOut() throws Exception {
        System.out.println("\n[2] Fan-out");
        final List<String> got = Collections.synchronizedList(new ArrayList<>());
        NodeRegistry reg = new NodeRegistry();
        reg.register(RecorderNode.class);
        reg.register(FilterNode.class);
        RuleEngine e = RuleEngine.builder().registry(reg)
                .expressionEngine(new SimpleExpressionEngine())
                .source(new InMemoryMessageSource("q"))
                .packTimeoutMs(5000)
                .capability(RecorderNode.Sink.class, new RecorderNode.Sink() {
                    public void accept(String s) { got.add(s); }
                }).build();
        // filter 的 True 同时连两个 recorder
        e.loadChain("{\"id\":\"c\",\"nodes\":{"
                + "\"f\":{\"type\":\"filter\",\"props\":{\"expr\":\"t > 5\"}},"
                + "\"x\":{\"type\":\"recorder\",\"props\":{\"tag\":\"X\"}},"
                + "\"y\":{\"type\":\"recorder\",\"props\":{\"tag\":\"Y\"}}},"
                + "\"edges\":{\"f\":[{\"to\":\"x\",\"relation\":\"True\"},{\"to\":\"y\",\"relation\":\"True\"}]},"
                + "\"entry\":[\"f\"]}");
        e.submitSync(RuleMessage.newMessage().type("T").originator("d").payload("{\"t\":\"9\"}").build());
        e.stop();
        check("一条消息发散到 2 个下游", got.size() == 2, got.toString());
    }

    // 3. 嵌套链：input -> 子链 -> output 回到调用者
    static void testNestedChain() throws Exception {
        System.out.println("\n[3] 嵌套链");
        final List<String> trace = Collections.synchronizedList(new ArrayList<>());
        NodeRegistry reg = new NodeRegistry();
        reg.register(RecorderNode.class);
        reg.register(SubChainNode.class);
        RuleEngine e = RuleEngine.builder().registry(reg)
                .expressionEngine(new SimpleExpressionEngine())
                .source(new InMemoryMessageSource("q"))
                .defaultChainId("main")
                .packTimeoutMs(5000)
                .capability(RecorderNode.Sink.class, new RecorderNode.Sink() {
                    public void accept(String s) { trace.add(s); }
                }).build();
        e.loadChain("{\"id\":\"main\",\"nodes\":{"
                + "\"s\":{\"type\":\"sub\"},"
                + "\"after\":{\"type\":\"recorder\",\"props\":{\"tag\":\"AFTER\"}}},"
                + "\"edges\":{\"s\":[{\"to\":\"after\"}]},\"entry\":[\"s\"]}");
        e.loadChain("{\"id\":\"sub\",\"nodes\":{"
                + "\"inner\":{\"type\":\"recorder\",\"props\":{\"tag\":\"INNER\"}}},"
                + "\"entry\":[\"inner\"]}");
        e.submitSync(RuleMessage.newMessage().type("T").originator("d").payload("x=1").build());
        e.stop();
        boolean hasInner = false, hasAfter = false;
        for (String t : trace) { if (t.startsWith("INNER")) hasInner = true; if (t.startsWith("AFTER")) hasAfter = true; }
        check("子链执行且回到主链下游", hasInner && hasAfter, trace.toString());
    }

    // 4. 失败重试：节点首次抛异常，重试后成功
    static void testFailureRetry() throws Exception {
        System.out.println("\n[4] 失败重试");
        final int[] calls = {0};
        NodeRegistry reg = new NodeRegistry();
        reg.register(FlakyNode.class);
        RuleEngine e = RuleEngine.builder().registry(reg)
                .expressionEngine(new SimpleExpressionEngine())
                .source(new InMemoryMessageSource("q"))
                .retryStrategy(RetryStrategy.retryFailed().maxRetries(3))
                .packTimeoutMs(5000)
                .capability(FlakyNode.FailSwitch.class, new FlakyNode.FailSwitch() {
                    public boolean shouldFail() { return ++calls[0] <= 2; }  // 前两次失败
                }).build();
        e.loadChain("{\"id\":\"c\",\"nodes\":{\"f\":{\"type\":\"flaky\"}},\"entry\":[\"f\"]}");
        e.submitSync(RuleMessage.newMessage().type("T").originator("d").payload("x=1").build());
        e.stop();
        check("重试后最终成功(调用3次)", calls[0] == 3, "实际调用 " + calls[0] + " 次");
    }

    // 5. 批次超时：节点卡住不返回，批次应超时而非永久挂起
    static void testPackTimeout() throws Exception {
        System.out.println("\n[5] 批次超时");
        NodeRegistry reg = new NodeRegistry();
        reg.register(HangNode.class);
        RuleEngine e = RuleEngine.builder().registry(reg)
                .expressionEngine(new SimpleExpressionEngine())
                .source(new InMemoryMessageSource("q"))
                .retryStrategy(RetryStrategy.skipAll())
                .packTimeoutMs(800)
                .build();
        e.loadChain("{\"id\":\"c\",\"nodes\":{\"h\":{\"type\":\"hang\"}},\"entry\":[\"h\"]}");
        long t0 = System.currentTimeMillis();
        e.submitSync(RuleMessage.newMessage().type("T").originator("d").payload("x=1").build());
        long cost = System.currentTimeMillis() - t0;
        e.stop();
        check("卡住节点触发批次超时(<3s)", cost < 3000, "耗时 " + cost + "ms");
    }

    // 6. 节点无状态：同一节点实例被多条消息复用，状态不串
    static void testNodeStateless() throws Exception {
        System.out.println("\n[6] 节点无状态");
        final List<String> seen = Collections.synchronizedList(new ArrayList<>());
        NodeRegistry reg = new NodeRegistry();
        reg.register(RecorderNode.class);
        RuleEngine e = RuleEngine.builder().registry(reg)
                .expressionEngine(new SimpleExpressionEngine())
                .source(new InMemoryMessageSource("q"))
                .packTimeoutMs(5000)
                .capability(RecorderNode.Sink.class, new RecorderNode.Sink() {
                    public void accept(String s) { seen.add(s); }
                }).build();
        e.loadChain("{\"id\":\"c\",\"nodes\":{\"r\":{\"type\":\"recorder\",\"props\":{\"tag\":\"S\"}}},\"entry\":[\"r\"]}");
        for (int i = 0; i < 20; i++) {
            e.submitSync(RuleMessage.newMessage().type("T").originator("d" + i)
                    .payload("n=" + i).build());
        }
        e.stop();
        check("20 条消息各自独立不串扰", seen.size() == 20, "实际 " + seen.size());
    }
}
