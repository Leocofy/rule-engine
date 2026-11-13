package io.fire.rule.core.runtime;

import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.runtime.strategy.BurstSubmitStrategy;
import io.fire.rule.core.spi.NodeException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 回归：验证 {@link RetryStrategy} 的批次级状态在 drop 后必须重置，
 * 否则一次重试耗尽会让后续所有批次直接放弃。
 */
public class RetryStrategyTest {

    private static Envelope envelope(String id) {
        return new Envelope(id, RuleMessage.newMessage().originator("o-" + id).build(), null);
    }

    /**
     * 构造一个批次：total 条消息，其中 failedCount 条已失败、successCount 条已成功，其余留在 pending。
     */
    private static PackContext pack(String prefix, int total, int failedCount, int successCount) {
        List<Envelope> envelopes = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            envelopes.add(envelope(prefix + i));
        }
        BurstSubmitStrategy strategy = new BurstSubmitStrategy();
        strategy.init(envelopes);
        PackContext ctx = new PackContext("test-queue", strategy);
        for (int i = 0; i < failedCount; i++) {
            ctx.onFailure(prefix + i, new RuntimeException("boom-" + i));
        }
        for (int i = failedCount; i < failedCount + successCount; i++) {
            ctx.onSuccess(prefix + i);
        }
        return ctx;
    }

    // Test scenario: 一次重试耗尽（RETRY_EXHAUSTED）drop 后，下一个失败批次仍应能重新进入重投。
    @Test
    public void retryExhaustedDropResetsStateForNextBatch() {
        RetryStrategy strategy = RetryStrategy.retryFailed().maxRetries(1);

        // 批次 1：首次失败进入重投
        PackContext batch1 = pack("a", 2, 1, 0);
        ProcessingDecision first = strategy.analyze(batch1);
        assertFalse(first.isCommit(), "首次失败应进入重投");
        assertEquals(1, strategy.retryCount(), "首次分析后重试计数应为 1");

        // 批次 1：再次分析超过 maxRetries=1，应放弃并重置批次级状态
        ProcessingDecision exhausted = strategy.analyze(batch1);
        assertTrue(exhausted.isCommit(), "重试耗尽应放弃并提交");
        assertEquals(ProcessingDecision.RETRY_EXHAUSTED, exhausted.dropReason(),
                "放弃原因应为重试次数耗尽");
        assertEquals(0, strategy.retryCount(), "drop 后 retryCount 必须归零");

        // 批次 2：另一个失败批次应重新进入重投，而不是直接 drop
        PackContext batch2 = pack("b", 2, 1, 0);
        ProcessingDecision next = strategy.analyze(batch2);
        assertFalse(next.isCommit(), "drop 后的新失败批次必须重新进入重投");
        assertFalse(next.getReprocessMap().isEmpty(), "重投决策应携带失败消息");
        assertEquals(1, next.getReprocessMap().size(), "本批次仅一条失败消息应被重投");
        assertEquals(1, strategy.retryCount(), "新批次应从 1 重新计数");
    }

    // Test scenario: 全成功批次应直接提交，并把重试计数清零。
    @Test
    public void successfulBatchCommitsAndResetsRetryCount() {
        RetryStrategy strategy = RetryStrategy.retryFailed();

        ProcessingDecision decision = strategy.analyze(pack("s", 3, 0, 3));

        assertTrue(decision.isCommit(), "全成功批次应直接提交");
        assertEquals(0, decision.dropReason(), "成功提交不应携带丢弃原因");
        assertEquals(0, strategy.retryCount(), "成功提交后重试计数应归零");
    }

    // Test scenario: 全部丢弃策略下，未完成消息不重投，直接按超时原因 drop。
    @Test
    public void skipAllDropsUnsettledBatchAsTimeout() {
        RetryStrategy strategy = RetryStrategy.skipAll();

        ProcessingDecision decision = strategy.analyze(pack("k", 2, 1, 0));

        assertTrue(decision.isCommit(), "skipAll 应直接提交（放弃）");
        assertEquals(ProcessingDecision.TIMEOUT, decision.dropReason(),
                "未配置重投超时项时应以 TIMEOUT 原因丢弃");
        assertTrue(decision.getReprocessMap().isEmpty(), "skipAll 不应产生重投消息");
        assertEquals(0, strategy.retryCount(), "drop 后重试计数应归零");
    }

    // Test scenario: 失败率超过阈值时应立即放弃，避免坏消息拖垮整批。
    @Test
    public void failureRateAboveThresholdDropsBatch() {
        RetryStrategy strategy = RetryStrategy.retryAll().maxFailurePercentage(0.3);

        // 10 条中 6 条成功、4 条未完成 => 失败率 0.4 > 0.3
        ProcessingDecision decision = strategy.analyze(pack("r", 10, 0, 6));

        assertTrue(decision.isCommit(), "失败率超阈值应放弃");
        assertEquals(ProcessingDecision.FAILURE_RATE_EXCEEDED, decision.dropReason(),
                "放弃原因应为失败率超阈值");
        assertEquals(0, strategy.retryCount(), "drop 后重试计数应归零");
    }

    // Test scenario: retryAll 应把失败、超时、成功三类消息全部收集为待重投。
    @Test
    public void retryAllCollectsFailedPendingAndSuccessfulMessages() {
        RetryStrategy strategy = RetryStrategy.retryAll();

        ProcessingDecision decision = strategy.analyze(pack("p", 3, 1, 1));

        assertFalse(decision.isCommit(), "retryAll 应进入重投");
        assertEquals(3, decision.getReprocessMap().size(),
                "失败 + 超时 + 成功三类消息都应被重投");
        assertEquals(1, strategy.retryCount());
    }

    // Test scenario: fatal 失败（NodeException.fatal=true）应直接放弃，不进入重试。
    @Test
    public void fatalFailureDropsWithoutRetry() {
        RetryStrategy strategy = RetryStrategy.retryFailed();
        PackContext ctx = packWithFailure("f", 2, new NodeException("端口写错", true));

        ProcessingDecision decision = strategy.analyze(ctx);

        assertTrue(decision.isCommit(), "fatal 失败应直接提交（放弃）");
        assertEquals(ProcessingDecision.FATAL_ERROR, decision.dropReason(),
                "放弃原因应为 FATAL_ERROR");
        assertTrue(decision.getReprocessMap().isEmpty(), "fatal 失败不应产生重投消息");
        assertEquals(0, strategy.retryCount(), "fatal 失败不应累加重试计数");
    }

    // Test scenario: fatal 失败发生在 fan-out 的 pendingFailure 中时同样应识别并放弃。
    @Test
    public void fatalFailureInPendingBranchDropsWithoutRetry() {
        List<Envelope> envelopes = new ArrayList<Envelope>();
        envelopes.add(envelope("g0"));
        BurstSubmitStrategy strategy = new BurstSubmitStrategy();
        strategy.init(envelopes);
        PackContext ctx = new PackContext("test-queue", strategy);
        ctx.onFanOut("g0", 2);
        ctx.onFailure("g0", new NodeException("坏端口", true));

        ProcessingDecision decision = RetryStrategy.retryFailed().analyze(ctx);

        assertTrue(decision.isCommit(), "pending 分支中的 fatal 失败也应放弃");
        assertEquals(ProcessingDecision.FATAL_ERROR, decision.dropReason());
    }

    // Test scenario: 非 fatal 失败仍按原策略进入重投。
    @Test
    public void nonFatalFailureStillRetries() {
        RetryStrategy strategy = RetryStrategy.retryFailed();
        PackContext ctx = packWithFailure("n", 2, new NodeException("临时故障", false));

        ProcessingDecision decision = strategy.analyze(ctx);

        assertFalse(decision.isCommit(), "非 fatal 失败应进入重投");
        assertEquals(1, decision.getReprocessMap().size(), "失败消息应被收集待重投");
        assertEquals(1, strategy.retryCount());
    }

    private static PackContext packWithFailure(String prefix, int total, Throwable error) {
        List<Envelope> envelopes = new ArrayList<Envelope>();
        for (int i = 0; i < total; i++) {
            envelopes.add(envelope(prefix + i));
        }
        BurstSubmitStrategy strategy = new BurstSubmitStrategy();
        strategy.init(envelopes);
        PackContext ctx = new PackContext("test-queue", strategy);
        ctx.onFailure(prefix + "0", error);
        return ctx;
    }
}