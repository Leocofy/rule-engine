package io.fire.rule.core.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 重试策略：整批处理失败后怎么办。
 *
 * <p><b>用三个布尔标志组合出多种行为，而不是写多个实现类</b>
 * （借鉴 ThingsBoard 的做法）：
 * <pre>
 *   retryFailed / retryTimeout / retrySuccessful
 *   RETRY_ALL                 = true, true, true
 *   RETRY_FAILED              = false, true, false
 *   RETRY_TIMED_OUT           = false, false, true
 *   RETRY_FAILED_AND_TIMEOUT  = false, true, true
 * </pre>
 * 新增策略只需加一个工厂方法，不需要新增类。
 */
public final class RetryStrategy {

    private final boolean retrySuccessful;
    private final boolean retryFailed;
    private final boolean retryTimeout;
    private final int maxRetries;
    private final double maxFailurePercentage;
    private final long pauseBetweenRetriesMs;
    private final long maxPauseBetweenRetriesMs;

    private int retryCount;
    private long currentPauseMs;
    private int initialTotalCount;

    private RetryStrategy(boolean retrySuccessful, boolean retryFailed, boolean retryTimeout,
                          int maxRetries, double maxFailurePercentage,
                          long pauseMs, long maxPauseMs) {
        this.retrySuccessful = retrySuccessful;
        this.retryFailed = retryFailed;
        this.retryTimeout = retryTimeout;
        this.maxRetries = maxRetries;
        this.maxFailurePercentage = maxFailurePercentage;
        this.pauseBetweenRetriesMs = pauseMs;
        this.maxPauseBetweenRetriesMs = maxPauseMs;
        this.currentPauseMs = pauseMs;
    }

    // ---------- 工厂方法 ----------

    /** 全部丢弃，适合开发/演示环境。 */
    public static RetryStrategy skipAll() {
        return new RetryStrategy(false, false, false, 0, 0, 0, 0);
    }

    /** 只重投失败项（推荐默认）。 */
    public static RetryStrategy retryFailed() {
        return new RetryStrategy(false, true, false, 3, 0, 1000, 5000);
    }

    /** 重投失败 + 超时项。 */
    public static RetryStrategy retryFailedAndTimeout() {
        return new RetryStrategy(false, true, true, 3, 0, 1000, 5000);
    }

    /** 重投整批（只要有一条失败），最保守。 */
    public static RetryStrategy retryAll() {
        return new RetryStrategy(true, true, true, 3, 0, 1000, 5000);
    }

    public RetryStrategy maxRetries(int maxRetries) {
        return new RetryStrategy(retrySuccessful, retryFailed, retryTimeout,
                maxRetries, maxFailurePercentage, pauseBetweenRetriesMs, maxPauseBetweenRetriesMs);
    }

    /** 失败率超过阈值就放弃，避免个别坏消息拖垮整批（如 DB 挂掉时）。 */
    public RetryStrategy maxFailurePercentage(double pct) {
        return new RetryStrategy(retrySuccessful, retryFailed, retryTimeout,
                maxRetries, pct, pauseBetweenRetriesMs, maxPauseBetweenRetriesMs);
    }

    // ---------- 决策 ----------

    public ProcessingDecision analyze(PackContext ctx) {
        if (ctx.isSuccess()) {
            resetBatchState();
            return ProcessingDecision.commit();
        }

        // fatal 失败（配置错误 / 端口写错 / 缺少依赖）重试没有意义，直接送死信
        if (ctx.hasFatalFailure()) {
            resetBatchState();
            return ProcessingDecision.drop(ProcessingDecision.FATAL_ERROR);
        }

        if (retryCount == 0) {
            initialTotalCount = ctx.pendingCount() + ctx.getFailedMap().size() + ctx.getSuccessMap().size();
        }
        retryCount++;

        // 超过最大重试次数 → 放弃，直接 commit（消息丢失）
        if (maxRetries > 0 && retryCount > maxRetries) {
            resetBatchState();
            return ProcessingDecision.drop(ProcessingDecision.RETRY_EXHAUSTED);
        }

        // 失败率超过阈值 → 放弃
        int failedCount = ctx.getFailedMap().size() + ctx.pendingCount();
        if (maxFailurePercentage > 0 && initialTotalCount > 0
                && ((double) failedCount / initialTotalCount) > maxFailurePercentage) {
            resetBatchState();
            return ProcessingDecision.drop(ProcessingDecision.FAILURE_RATE_EXCEEDED);
        }

        // 按标志收集待重投消息
        Map<String, Envelope> toReprocess = new LinkedHashMap<>();
        if (retryFailed) {
            toReprocess.putAll(ctx.getFailedMap());
        }
        if (retryTimeout) {
            toReprocess.putAll(ctx.pendingMapSnapshot());
        }
        if (retrySuccessful) {
            toReprocess.putAll(ctx.getSuccessMap());
        }
        if (toReprocess.isEmpty()) {
            // 既失败/超时，又没配置重投 → 这些消息会被丢弃，必须送死信。
            // 典型场景：retryFailed() 不重投超时项，超时消息即走到这里。
            boolean allSettled = ctx.getFailedMap().isEmpty() && ctx.pendingCount() == 0;
            resetBatchState();
            return allSettled
                    ? ProcessingDecision.commit()
                    : ProcessingDecision.drop(ProcessingDecision.TIMEOUT);
        }

        sleepBeforeRetry();
        return ProcessingDecision.reprocess(toReprocess);
    }

    /**
     * 重置批次级状态：retryCount 必须随批次结束归零，否则一次重试耗尽后，
     * 后续批次会带着超限的计数直接放弃，永远不再重试。
     */
    private void resetBatchState() {
        retryCount = 0;
        currentPauseMs = pauseBetweenRetriesMs;
        initialTotalCount = 0;
    }

    public int retryCount() {
        return retryCount;
    }

    private void sleepBeforeRetry() {
        if (currentPauseMs <= 0) {
            return;
        }
        try {
            Thread.sleep(currentPauseMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        // 指数退避并封顶
        if (maxPauseBetweenRetriesMs > currentPauseMs) {
            currentPauseMs = Math.min(maxPauseBetweenRetriesMs, currentPauseMs * 2);
        }
    }
}
