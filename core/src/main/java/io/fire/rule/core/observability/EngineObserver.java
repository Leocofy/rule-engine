package io.fire.rule.core.observability;

import io.fire.rule.core.model.RuleMessage;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 引擎可观测性扩展点：统计、debug 快照、链路追踪都从这里接入。
 *
 * <p>做成接口而非硬编码日志，是为了让"监控"这件事可插拔：
 * 测试环境打印日志，生产环境接 Micrometer / Prometheus。
 *
 * <p><b>统一 State 模型（D4）</b>：节点级钩子改为携带 {@link NodeDelta}（本跳增量），
 */
public interface EngineObserver {

    /**
     * 节点开始执行：只有需要逐节点追踪（设计器试跑、链路回放）时才关心。
     */
    default void onNodeStarted(NodeDelta delta) {
    }

    /**
     * 节点完成路由：带本跳产出 + 端口 + 下游目标，说明"走了哪条分支"。
     */
    default void onNodeRouted(NodeDelta delta) {
    }

    /**
     * 节点执行结束：带本跳产出 + 耗时，说明"耗时多少"。
     */
    void onNodeFinished(NodeDelta delta);

    void onNodeError(String chainId, String nodeId, Object input, Throwable t);

    void onPackTimeout(String queueName, int pendingCount);

    void onPackFailed(String queueName, int failedCount, List<String> samples);

    void onPackFinished(String queueName, int total, boolean committed);

    /**
     * 消息被送进死信队列。
     *
     * <p><b>告警级事件</b>：意味着这条消息没走完正常处理链路。
     * 生产环境应接入监控——持续出现说明有节点或下游故障。
     */
    void onDeadLetter(String queueName, int reason, RuleMessage msg, Throwable error);

    /**
     * 开箱可用的统计实现：记录每个节点的执行次数、耗时、错误数。
     * 排查"哪个节点拖慢了整条链"时非常有用。
     */
    class Stats implements EngineObserver {

        private final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<String, AtomicLong>();
        private final ConcurrentHashMap<String, AtomicLong> errors = new ConcurrentHashMap<String, AtomicLong>();
        private final ConcurrentHashMap<String, AtomicLong> totalNanos = new ConcurrentHashMap<String, AtomicLong>();
        private final AtomicLong deadLetters = new AtomicLong();

        @Override
        public void onNodeFinished(NodeDelta delta) {
            String key = delta.chainId() + "/" + delta.nodeId();
            counts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
            totalNanos.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta.nanos());
        }

        @Override
        public void onNodeError(String chainId, String nodeId, Object input, Throwable t) {
            errors.computeIfAbsent(chainId + "/" + nodeId, k -> new AtomicLong()).incrementAndGet();
        }

        @Override
        public void onPackTimeout(String queueName, int pendingCount) {
            System.out.println("Pack timeout: " + queueName + " pendingCount=" + pendingCount);
        }

        @Override
        public void onPackFailed(String queueName, int failedCount, List<String> samples) {
            System.out.println("Pack failed: " + queueName + " failedCount=" + failedCount + " samples=" + samples);
        }

        @Override
        public void onPackFinished(String queueName, int total, boolean committed) {
            System.out.println("Pack finished: " + queueName + " total=" + total + " committed=" + committed);
        }

        @Override
        public void onDeadLetter(String queueName, int reason, RuleMessage msg, Throwable error) {
            deadLetters.incrementAndGet();
        }

        /**
         * 输出节点耗时排行，找出瓶颈节点。
         */
        public String report() {
            StringBuilder sb = new StringBuilder("节点执行统计(平均耗时降序):\n");
            counts.entrySet().stream()
                    .sorted((a, b) -> Double.compare(avgMs(b.getKey()), avgMs(a.getKey())))
                    .forEach(e -> sb.append("  ")
                            .append(e.getKey())
                            .append("  count=").append(e.getValue().get())
                            .append("  avg=").append(String.format("%.3f", avgMs(e.getKey()))).append("ms")
                            .append("  errors=").append(errors.getOrDefault(e.getKey(), new AtomicLong()).get())
                            .append("  deadLetters=").append(deadLetters.get())
                            .append('\n'));
            return sb.toString();
        }

        private double avgMs(String key) {
            AtomicLong c = counts.get(key);
            AtomicLong n = totalNanos.get(key);
            if (c == null || n == null || c.get() == 0) {
                return 0d;
            }
            return (n.get() / (double) c.get()) / 1_000_000d;
        }
    }
}
