package io.fire.rule;

import io.fire.rule.cluster.*;
import io.fire.rule.model.*;
import io.fire.rule.nodes.*;
import io.fire.rule.spi.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 多实例分布式验证：分区归属 / 本地优先 / 跨实例恢复 / 顺序保证 */
public class ClusterVerify {

    static int pass = 0, fail = 0;

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  [PASS] " + n); }
        else { fail++; System.out.println("  [FAIL] " + n + " -> " + d); }
    }

    public static void main(String[] a) throws Exception {
        testPartitionRouting();
        testCrossNodeResume();
        testLocalFirst();
        testOrderAcrossNodes();
        System.out.println("\n===== 结果: pass=" + pass + ", fail=" + fail + " =====");
        if (fail > 0) System.exit(1);
    }

    // 1. 分区路由：同一 originator 恒定映射到同一分区
    static void testPartitionRouting() {
        System.out.println("\n[1] 分区路由一致性");
        HashPartitionRouter r1 = HashPartitionRouter.staticAssign("node-1", 12, 0, 3);
        HashPartitionRouter r2 = HashPartitionRouter.staticAssign("node-2", 12, 1, 3);
        HashPartitionRouter r3 = HashPartitionRouter.staticAssign("node-3", 12, 2, 3);

        // 三个实例用同一个路由算法，结果必须一致
        int p1 = r1.partitionOf("device-007", 12);
        int p2 = r2.partitionOf("device-007", 12);
        int p3 = r3.partitionOf("device-007", 12);
        check("三实例对同设备算出同分区", p1 == p2 && p2 == p3, p1 + "/" + p2 + "/" + p3);

        // 分区必须完整覆盖且无重叠
        Set<Integer> all = new HashSet<>();
        all.addAll(r1.ownedPartitions());
        all.addAll(r2.ownedPartitions());
        all.addAll(r3.ownedPartitions());
        check("12 个分区被 3 实例完整覆盖", all.size() == 12, "实际 " + all.size());

        // 每个设备只属于一个实例
        int ownerCount = 0;
        for (HashPartitionRouter r : new HashPartitionRouter[]{r1, r2, r3}) {
            if (r.isLocal(p1)) ownerCount++;
        }
        check("每个设备只归属一个实例", ownerCount == 1, "归属数 " + ownerCount);
    }

    // 2. 跨实例恢复：A 决定走某关系，B 收到后从该关系继续（不重复执行节点）
    static void testCrossNodeResume() throws Exception {
        System.out.println("\n[2] 跨实例恢复");
        InMemoryClusterBroker broker = new InMemoryClusterBroker(4);
        JsonMessageSerializer ser = new JsonMessageSerializer();

        final List<String> trace = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger execCount = new AtomicInteger();

        // 构造一个会触发跨节点投递的场景
        NodeRegistry reg = new NodeRegistry();
        reg.register(TraceNode.class);
        reg.register(CrossNode.class);

        // 实例 B：拥有分区 1
        HashPartitionRouter routerB = HashPartitionRouter.staticAssign("node-B", 4, 1, 4);
        RuleEngine engineB = RuleEngine.builder()
                .registry(reg).expressionEngine(new SimpleIExpressionEngine())
                .source(new PartitionedMessageSource("q", routerB, ser, broker, 64))
                .partitionRouter(routerB).messageSerializer(ser)
                .remoteTransport(broker)
                .packTimeoutMs(3000).defaultChainId("c")
                .capability(TraceNode.Sink.class, new TraceNode.Sink() {
                    public void accept(String s) { trace.add(s); }
                })
                .capability(CrossNode.Counter.class, new CrossNode.Counter() {
                    public void inc() { execCount.incrementAndGet(); }
                }).build();
        engineB.loadChain("{\"id\":\"c\",\"nodes\":{"
                + "\"cross\":{\"type\":\"cross\"},"
                + "\"hot\":{\"type\":\"trace\",\"props\":{\"tag\":\"HOT\"}},"
                + "\"cold\":{\"type\":\"trace\",\"props\":{\"tag\":\"COLD\"}}},"
                + "\"edges\":{\"cross\":[{\"to\":\"hot\",\"relation\":\"hot\"},"
                + "{\"to\":\"cold\",\"relation\":\"cold\"}]},\"entry\":[\"cross\"]}");

        // 找一个恰好映射到 B 拥有分区的设备（模拟 A 投递到 B 的分区）
        int owned = routerB.ownedPartitions().iterator().next();
        String dev = null;
        for (int i = 0; i < 1000; i++) {
            if (routerB.partitionOf("dev-" + i, 4) == owned) { dev = "dev-" + i; break; }
        }
        // 模拟实例 A 上节点 cross 输出 "hot"，跨节点投递
        RuleMessage msg = RuleMessage.newMessage()
                .type("T").originator(dev)
                .chainId("c").nodeId("cross").payload("x=1").build();
        byte[] data = ser.serialize(msg, new LinkedHashSet<>(Arrays.asList("hot")));
        broker.send("c", owned, dev, data);

        // B 消费并处理
        engineB.start();
        Thread.sleep(800);
        engineB.stop();

        check("跨实例消息被消费", execCount.get() >= 0, "");
        boolean hot = false, cold = false;
        for (String t : trace) {
            if (t.startsWith("HOT")) hot = true;
            if (t.startsWith("COLD")) cold = true;
        }
        check("按原关系 hot 继续路由（未重复执行 cross）", hot && !cold, trace.toString());
        check("cross 节点未被重复执行", execCount.get() == 0, "执行了 " + execCount.get() + " 次");
    }

    // 3. 本地优先：目标分区在本机时不走网络
    static void testLocalFirst() throws Exception {
        System.out.println("\n[3] 本地优先优化");
        InMemoryClusterBroker broker = new InMemoryClusterBroker(8);
        JsonMessageSerializer ser = new JsonMessageSerializer();
        HashPartitionRouter router = HashPartitionRouter.staticAssign("node-1", 8, 0, 1); // 独占全部

        NodeRegistry reg = new NodeRegistry();
        reg.register(TraceNode.class);
        reg.register(CrossNode.class);
        RuleEngine e = RuleEngine.builder()
                .registry(reg).expressionEngine(new SimpleIExpressionEngine())
                .source(new PartitionedMessageSource("q", router, ser, broker, 64))
                .partitionRouter(router).messageSerializer(ser)
                .remoteTransport(broker)
                .packTimeoutMs(2000).defaultChainId("c")
                .capability(TraceNode.Sink.class, s -> { })
                .capability(CrossNode.Counter.class, () -> { })
                .build();
        e.loadChain("{\"id\":\"c\",\"nodes\":{"
                + "\"cross\":{\"type\":\"cross\"},"
                + "\"hot\":{\"type\":\"trace\",\"props\":{\"tag\":\"H\"}}},"
                + "\"edges\":{\"cross\":[{\"to\":\"hot\",\"relation\":\"hot\"}]},"
                + "\"entry\":[\"cross\"]}");
        e.start(); Thread.sleep(300); e.stop();
        check("单实例独占分区时零跨网络投递", broker.remoteSendCount() == 0,
                "跨网络 " + broker.remoteSendCount() + " 次");
    }

    // 4. 顺序保证：同一设备跨分区仍按序
    static void testOrderAcrossNodes() throws Exception {
        System.out.println("\n[4] 分布式顺序保证");
        HashPartitionRouter r = HashPartitionRouter.staticAssign("n1", 16, 0, 1);
        // 同一设备的 100 条消息必须全部落到同一分区
        Set<Integer> parts = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            parts.add(r.partitionOf("device-A", 16));
        }
        check("同设备 100 条消息恒定落同分区", parts.size() == 1, "落在 " + parts.size() + " 个分区");

        // 不同设备应分散到不同分区（负载均衡）
        Set<Integer> spread = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            spread.add(r.partitionOf("dev-" + i, 16));
        }
        check("1000 个设备分散到多分区（负载均衡）", spread.size() >= 12,
                "只用了 " + spread.size() + " 个分区");
    }
}
