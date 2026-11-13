package io.fire.rule.core.runtime;

import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.runtime.strategy.BurstSubmitStrategy;
import io.fire.rule.core.spi.NodeException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 回归：验证 {@link PackContext} 的 fan-out 令牌计数。
 *
 * <p>核心契约：一条消息派生出 N 个分支时，必须等全部 N 个分支完成后批次才收口；
 * 同一消息在分支内部再次 fan-out 时令牌应累加而非覆盖。
 */
public class PackContextTest {

    private static Envelope envelope(String id) {
        return new Envelope(id, RuleMessage.newMessage().originator("o-" + id).build(), null);
    }

    private static PackContext context(String... ids) {
        Envelope[] envelopes = new Envelope[ids.length];
        for (int i = 0; i < ids.length; i++) {
            envelopes[i] = envelope(ids[i]);
        }
        BurstSubmitStrategy strategy = new BurstSubmitStrategy();
        strategy.init(Arrays.asList(envelopes));
        return new PackContext("test-queue", strategy);
    }

    // Test scenario: 2 个分支只完成 1 个时批次不得收口，全部完成后 latch 必须归零。
    @Test
    public void fanOutOfTwoBranchesReleasesLatchOnlyAfterAllComplete() throws Exception {
        PackContext ctx = context("m1");

        ctx.onFanOut("m1", 2);
        ctx.onSuccess("m1");

        assertFalse(ctx.await(50L), "仅完成一个分支时批次不应收口");
        assertEquals(1, ctx.pendingCount(), "仍有未完成分支时该消息应保留在 pending");

        ctx.onSuccess("m1");

        assertTrue(ctx.await(1000L), "两个分支全部完成后 latch 必须归零");
        assertEquals(0, ctx.pendingCount(), "全部完成后 pending 应为空");
        assertEquals(1, ctx.getSuccessMap().size(), "成功消息应进入 success 表");
        assertTrue(ctx.isSuccess());
        assertTrue(ctx.pendingMapSnapshot().isEmpty());
    }

    // Test scenario: 嵌套 fan-out（同一消息二次声明分支）令牌必须累加，不能被新计数覆盖。
    @Test
    public void nestedFanOutAccumulatesTokensInsteadOfOverwriting() throws Exception {
        PackContext ctx = context("m1");

        ctx.onFanOut("m1", 2);
        // 分支内部再次 fan-out：期望总令牌 = 1(初始) + 1 + 1 = 3
        ctx.onFanOut("m1", 2);

        ctx.onSuccess("m1");
        assertFalse(ctx.await(50L), "第一个令牌消耗后不应收口");

        ctx.onSuccess("m1");
        assertFalse(ctx.await(50L), "若计数被覆盖，此处会提前收口；实际应仍需一个令牌");

        ctx.onSuccess("m1");
        assertTrue(ctx.await(1000L), "三个令牌全部消耗后应收口");
        assertEquals(0, ctx.pendingCount());
        assertTrue(ctx.isSuccess());
    }

    // Test scenario: 分支中存在失败时，仍需等其余分支完成才收口，且最终整体判定为失败。
    @Test
    public void fanOutWithFailureClosesAsFailedAfterAllBranchesDone() throws Exception {
        PackContext ctx = context("m1");
        RuntimeException boom = new RuntimeException("boom");

        ctx.onFanOut("m1", 3);
        ctx.onFailure("m1", boom);
        assertFalse(ctx.await(50L), "失败分支不应使其余在途分支提前收口");

        ctx.onSuccess("m1");
        assertFalse(ctx.await(50L));

        ctx.onSuccess("m1");
        assertTrue(ctx.await(1000L), "最后一个分支完成后即使存在失败也应收口");

        assertFalse(ctx.isSuccess(), "存在失败消息时整批不应判定成功");
        assertEquals(1, ctx.getFailedMap().size(), "失败消息应进入 failed 表");
        assertSame(boom, ctx.errorOf("m1"), "失败原因应可回查");
        assertTrue(ctx.getSuccessMap().isEmpty(), "先失败后成功不应写入 success 表");
        assertEquals(0, ctx.pendingCount());
    }

    // Test scenario: 分支数为 1 时 onFanOut 应被忽略，完成后立即可收口。
    @Test
    public void singleBranchFanOutDeclarationIsIgnored() throws Exception {
        PackContext ctx = context("m1");

        ctx.onFanOut("m1", 1);
        ctx.onSuccess("m1");

        assertTrue(ctx.await(1000L), "单分支无需额外令牌，完成即可收口");
        assertTrue(ctx.isSuccess());
        assertEquals(0, ctx.pendingCount());
    }

    // Test scenario: 同一消息重复回调成功时，第二次应为空操作，不重复计数。
    @Test
    public void duplicateSuccessAfterCompletionIsNoOp() throws Exception {
        PackContext ctx = context("m1");

        ctx.onSuccess("m1");
        ctx.onSuccess("m1");

        assertTrue(ctx.await(1000L), "首次成功即应收口");
        assertEquals(1, ctx.getSuccessMap().size(), "重复成功回调不应重复记录");
        assertEquals(0, ctx.pendingCount());
    }

    // Test scenario: hasFatalFailure 只对 fatal 的 NodeException 返回 true。
    @Test
    public void hasFatalFailureDistinguishesFatalNodeException() {
        PackContext fatalCtx = context("f1");
        fatalCtx.onFailure("f1", new NodeException("bad port", true));
        assertTrue(fatalCtx.hasFatalFailure(), "fatal NodeException 应被识别");

        PackContext normalCtx = context("n1");
        normalCtx.onFailure("n1", new RuntimeException("boom"));
        assertFalse(normalCtx.hasFatalFailure(), "普通异常不应视为 fatal");

        PackContext nonFatalNodeCtx = context("n2");
        nonFatalNodeCtx.onFailure("n2", new NodeException("config", false));
        assertFalse(nonFatalNodeCtx.hasFatalFailure(), "isFatal=false 的 NodeException 不应视为 fatal");
    }
}