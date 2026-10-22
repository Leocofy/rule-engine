package io.fire.rule.example.stress;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.observability.EngineObserver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 压测观察者：在节点耗时统计之外补充批次吞吐、超时、死信与实时 TPS。
 *
 * <p>刻意不透传 {@code onPackFinished / onPackFailed / onPackTimeout}，
 * 避免默认 Stats 实现的 System.out 打印在高吞吐下成为瓶颈；
 * 节点级统计仍然委托给内部 {@link Stats}，保留瓶颈节点排行。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
public class StressEngineObserver implements EngineObserver {

    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int MAX_SAMPLES = 2000;

    private final Stats delegate = new Stats();
    private final AtomicLong rounds = new AtomicLong();
    private final AtomicLong committedPacks = new AtomicLong();
    private final AtomicLong messages = new AtomicLong();
    private final AtomicLong failedPacks = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong deadLetters = new AtomicLong();
    private final AtomicLong nodeErrors = new AtomicLong();
    private final AtomicLong firstCommittedNanos = new AtomicLong();

    /**
     * 每个元素为 [纳秒时间戳, 累计已提交消息数]，用于计算最近 5 秒吞吐。
     */
    private final ConcurrentLinkedDeque<long[]> window = new ConcurrentLinkedDeque<>();

    @Override
    public void onNodeFinished(String chainId, String nodeId, long nanos) {
        delegate.onNodeFinished(chainId, nodeId, nanos);
    }

    @Override
    public void onNodeError(String chainId, String nodeId, Throwable t) {
        nodeErrors.incrementAndGet();
        delegate.onNodeError(chainId, nodeId, t);
    }

    @Override
    public void onPackTimeout(String queueName, int pendingCount) {
        timeouts.incrementAndGet();
    }

    @Override
    public void onPackFailed(String queueName, int failedCount, List<String> samples) {
        failedPacks.incrementAndGet();
    }

    @Override
    public void onPackFinished(String queueName, int total, boolean committed) {
        rounds.incrementAndGet();
        if (!committed) {
            return;
        }
        long now = System.nanoTime();
        long done = messages.addAndGet(total);
        committedPacks.incrementAndGet();
        firstCommittedNanos.compareAndSet(0L, now);
        window.addLast(new long[]{now, done});
        while (window.size() > MAX_SAMPLES) {
            window.pollFirst();
        }
    }

    @Override
    public void onDeadLetter(String queueName, int reason, RuleMessage msg, Throwable error) {
        deadLetters.incrementAndGet();
        delegate.onDeadLetter(queueName, reason, msg, error);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("rounds", rounds.get());
        snapshot.put("committedPacks", committedPacks.get());
        snapshot.put("messages", messages.get());
        snapshot.put("failedPacks", failedPacks.get());
        snapshot.put("timeouts", timeouts.get());
        snapshot.put("deadLetters", deadLetters.get());
        snapshot.put("nodeErrors", nodeErrors.get());
        snapshot.put("tpsWindow", round2(currentTps()));
        snapshot.put("tpsOverall", round2(overallTps()));
        snapshot.put("nodes", delegate.report());
        return snapshot;
    }

    public long messages() {
        return messages.get();
    }

    public long deadLetters() {
        return deadLetters.get();
    }

    public long timeouts() {
        return timeouts.get();
    }

    public String nodeReport() {
        return delegate.report();
    }

    /**
     * 清零本观察者的速率统计；节点耗时排行是进程累计值，不在此处重置。
     */
    public void reset() {
        rounds.set(0);
        committedPacks.set(0);
        messages.set(0);
        failedPacks.set(0);
        timeouts.set(0);
        deadLetters.set(0);
        nodeErrors.set(0);
        firstCommittedNanos.set(0);
        window.clear();
    }

    private double currentTps() {
        long now = System.nanoTime();
        // 只保留最近 5 秒的样本，且至少留一个基准点
        while (window.size() > 2) {
            long[] first = window.peekFirst();
            if (first == null || now - first[0] <= WINDOW_NANOS) {
                break;
            }
            window.pollFirst();
        }
        long[] first = window.peekFirst();
        long[] last = window.peekLast();
        if (first == null || last == null || last[0] <= first[0] || now - last[0] > WINDOW_NANOS) {
            return 0d;
        }
        return (last[1] - first[1]) * 1_000_000_000d / (last[0] - first[0]);
    }

    private double overallTps() {
        long start = firstCommittedNanos.get();
        if (start == 0L) {
            return 0d;
        }
        long elapsed = System.nanoTime() - start;
        return elapsed <= 0L ? 0d : messages.get() * 1_000_000_000d / elapsed;
    }

    private double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
