package io.fire.rule.runtime;

import io.fire.rule.model.PackCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批次处理上下文：整批消息的成败账本。
 *
 * <p>三个映射表的分工（借鉴 ThingsBoard 的设计）：
 * <ul>
 *   <li><b>pending</b> 还没回音 —— 处理中或已超时</li>
 *   <li><b>success</b> 已成功</li>
 *   <li><b>failed</b> 已失败</li>
 * </ul>
 *
 * <p><b>超时不单独记表</b>：超时 = "到期了还留在 pending 里"。
 * 这样判断超时不需要额外定时器，await 结束时看 pending 剩什么即可。
 *
 * <p><b>为什么用 CountDownLatch(1) + AtomicInteger</b>：
 * 每条回来减一，减到 0 立刻放行，不必死等超时时间。
 */
public class PackContext {

    private final String queueName;
    private final SubmitStrategy submitStrategy;
    private final AtomicInteger pendingCount;
    private final CountDownLatch latch = new CountDownLatch(1);

    private final Map<String, Envelope> pending;
    private final Map<String, Envelope> success = new ConcurrentHashMap<>();
    private final Map<String, Envelope> failed = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();

    /**
     * 每条消息派生出的分支剩余数（fan-out 场景）。
     */
    private final Map<String, AtomicInteger> fanOutRemaining = new ConcurrentHashMap<>();
    /**
     * 某分支已失败，但需等其余分支完成才能收口。
     */
    private final Map<String, Throwable> pendingFailure = new ConcurrentHashMap<>();

    private volatile boolean canceled;
    private volatile boolean committed;

    public PackContext(String queueName, SubmitStrategy submitStrategy) {
        this.queueName = queueName;
        this.submitStrategy = submitStrategy;
        this.pending = submitStrategy.pendingMap();
        this.pendingCount = new AtomicInteger(pending.size());
    }

    /**
     * 等待整批处理完成，返回 false 表示超时。
     */
    public boolean await(long timeoutMs) throws InterruptedException {
        boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        if (ok) {
            committed = true;
        }
        return ok;
    }

    /**
     * 声明派生分支数：增加待完成计数，避免先完成的分支提前收口。
     */
    public void onFanOut(String id, int branches) {
        if (branches <= 1) {
            return;
        }
        fanOutRemaining.put(id, new AtomicInteger(branches));
        pendingCount.addAndGet(branches - 1);
    }

    /**
     * 该分支已完成；返回 true 表示所有分支都完成，可以收口。
     */
    private boolean branchDone(String id) {
        java.util.concurrent.atomic.AtomicInteger r = fanOutRemaining.get(id);
        if (r == null) {
            return true;
        }
        if (r.decrementAndGet() > 0) {
            return false;
        }
        fanOutRemaining.remove(id);
        return true;
    }

    public void onSuccess(String id) {
        if (!branchDone(id)) {
            return;   // 还有其他分支在跑
        }
        Throwable earlier = pendingFailure.remove(id);
        if (earlier != null) {
            finishAsFailed(id, earlier);
            return;
        }
        Envelope removed = pending.remove(id);
        if (removed == null) {
            return;
        }
        success.put(id, removed);
        submitStrategy.onSuccess(id);
        if (pendingCount.decrementAndGet() == 0) {
            latch.countDown();
        }
    }

    private void finishAsFailed(String id, Throwable t) {
        finishAsFailed(id, t, true);
    }

    private void finishAsFailed(String id, Throwable t, boolean release) {
        Envelope removed = pending.remove(id);
        if (removed == null) {
            return;
        }
        failed.put(id, removed);
        failures.put(id, t == null ? "unknown" : String.valueOf(t.getMessage()));
        if (release) {
            submitStrategy.onFailure(id);
        }
        if (pendingCount.decrementAndGet() == 0) {
            latch.countDown();
        }
    }

    public void onFailure(String id, Throwable t) {
        if (!branchDone(id)) {
            // 记录失败，等其余分支完成后再统一判失败
            pendingFailure.put(id, t);
            return;
        }
        finishAsFailed(id, t);
    }

    /**
     * 超时后剩余在 pending 中的即为超时消息。
     */
    public int pendingCount() {
        return pending.size();
    }

    public boolean isSuccess() {
        return pending.isEmpty() && failed.isEmpty();
    }

    public void cancel() {
        canceled = true;
        latch.countDown();
    }

    public boolean isCanceled() {
        return canceled;
    }

    public boolean isCommitted() {
        return committed;
    }

    public void cleanup() {
        pending.clear();
        success.clear();
        failed.clear();
        failures.clear();
        fanOutRemaining.clear();
        pendingFailure.clear();
    }

    public String getQueueName() {
        return queueName;
    }

    public Map<String, Envelope> getSuccessMap() {
        return success;
    }

    public Map<String, Envelope> getFailedMap() {
        return failed;
    }

    /**
     * pending 的不可变快照：超时消息就是到期后仍留在 pending 中的那些。
     */
    public Map<String, Envelope> pendingMapSnapshot() {
        return new ConcurrentHashMap<>(pending);
    }

    /**
     * 失败原因摘要，用于日志与统计。
     */
    public List<String> failureSummary(int limit) {
        List<String> out = new ArrayList<>();
        int n = 0;
        for (Map.Entry<String, String> e : failures.entrySet()) {
            if (n++ >= limit) {
                break;
            }
            out.add(e.getKey() + " : " + e.getValue());
        }
        return out;
    }

    /**
     * 消息级的回调桥梁，注入到 RuleMessage 中供节点回调。
     */
    public PackCallback callbackFor(final String id) {
        return new PackCallback() {
            @Override
            public void onSuccess() {
                PackContext.this.onSuccess(id);
            }

            @Override
            public void onFailure(Throwable t) {
                PackContext.this.onFailure(id, t);
            }

            @Override
            public void onFanOut(int branches) {
                PackContext.this.onFanOut(id, branches);
            }

            @Override
            public boolean isValid() {
                return !canceled;
            }
        };
    }
}
