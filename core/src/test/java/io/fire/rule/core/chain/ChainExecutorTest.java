package io.fire.rule.core.chain;

import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.spi.IExpressionEngine;
import io.fire.rule.core.spi.NodeException;
import io.fire.rule.core.spi.RuleNode;
import io.fire.rule.core.store.IChainStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 1 护栏回归：验证 {@link ChainExecutor#dispatch} 的出口缺失检测与端口严格校验。
 */
public class ChainExecutorTest {

    private ChainExecutor executor;

    @AfterEach
    public void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    // Test scenario: 节点 onMsg 既不投递也未调用 asyncExecutor() 时，应上报 fatal 的出口缺失异常。
    @Test
    public void missingExitProducesFatalNodeException() {
        RuleChain chain = chainWithSource("n1", ctx -> {
        });
        RecordingCallback callback = new RecordingCallback();
        executor = newExecutor(chain, false);

        executor.dispatch(chain, "n1", message(), new ExecutionContext(callback));

        assertEquals(1, callback.errorCount.get(), "出口缺失应回调 onError");
        Throwable error = callback.lastError.get();
        assertTrue(error instanceof NodeException, "出口缺失应抛 NodeException");
        assertTrue(((NodeException) error).isFatal(), "出口缺失必须是 fatal");
        assertTrue(error.getMessage().contains("未产生任何出口"), "错误信息应说明出口缺失");
        assertEquals(0, callback.completeCount.get(), "出口缺失不应收口");
    }

    // Test scenario: 调用过 asyncExecutor() 的异步节点允许 onMsg 返回时暂时没有出口。
    @Test
    public void asyncDelegatedNodeIsAllowedToReturnWithoutExit() {
        RuleChain chain = chainWithSource("n1", ctx -> ctx.asyncExecutor());
        RecordingCallback callback = new RecordingCallback();
        executor = newExecutor(chain, false);

        executor.dispatch(chain, "n1", message(), new ExecutionContext(callback));

        assertEquals(0, callback.errorCount.get(), "异步委派节点不应触发出口缺失");
        assertEquals(0, callback.completeCount.get(), "异步委派本身不收口");
    }

    // Test scenario: 正常单次 ack 出口不应触发出口缺失护栏。
    @Test
    public void normalSingleExitDoesNotTriggerMissingExitGuard() {
        RuleChain chain = chainWithSource("n1", ctx -> ctx.ack());
        RecordingCallback callback = new RecordingCallback();
        executor = newExecutor(chain, false);

        executor.dispatch(chain, "n1", message(), new ExecutionContext(callback));

        assertEquals(0, callback.errorCount.get(), "正常出口不应报错");
        assertEquals(1, callback.completeCount.get(), "ack 应收口");
    }

    // Test scenario: strictPortCheck=true 时使用未在出边中声明的端口应抛 fatal NodeException。
    @Test
    public void strictPortCheckRejectsUndeclaredPort() {
        RuleChain chain = new RuleChain("c1", "test");
        chain.addNode("n1", "source", ctx -> ctx.next("unknown"), null);
        chain.addNode("n2", "sink", ctx -> ctx.ack(), null);
        chain.connect("n1", "default", "n2");
        chain.addEntry("n1");
        RecordingCallback callback = new RecordingCallback();
        executor = newExecutor(chain, true);

        executor.dispatch(chain, "n1", message(), new ExecutionContext(callback));

        assertEquals(1, callback.errorCount.get(), "未声明端口应回调 onError");
        Throwable error = callback.lastError.get();
        assertTrue(error instanceof NodeException, "未声明端口应抛 NodeException");
        assertTrue(((NodeException) error).isFatal(), "未声明端口必须是 fatal");
        assertTrue(error.getMessage().contains("未声明的端口"), "错误信息应说明端口未声明");
    }

    // Test scenario: strictPortCheck=false 时未声明端口放行，走到链路尽头后正常收口。
    @Test
    public void strictPortCheckDisabledAllowsUndeclaredPort() {
        RuleChain chain = new RuleChain("c1", "test");
        chain.addNode("n1", "source", ctx -> ctx.next("unknown"), null);
        chain.addNode("n2", "sink", ctx -> ctx.ack(), null);
        chain.connect("n1", "default", "n2");
        chain.addEntry("n1");
        RecordingCallback callback = new RecordingCallback();
        executor = newExecutor(chain, false);

        executor.dispatch(chain, "n1", message(), new ExecutionContext(callback));

        assertEquals(0, callback.errorCount.get(), "关闭严格端口校验时不应报错");
        assertEquals(1, callback.completeCount.get(), "无下游时应回到顶层收口");
    }

    // Test scenario: 节点没有任何出边时，使用任意端口都不应报错。
    @Test
    public void nodeWithoutOutgoingEdgesAllowsAnyPort() {
        RuleChain chain = chainWithSource("n1", ctx -> ctx.next("anything"));
        RecordingCallback callback = new RecordingCallback();
        executor = newExecutor(chain, true);

        executor.dispatch(chain, "n1", message(), new ExecutionContext(callback));

        assertEquals(0, callback.errorCount.get(), "无出边节点不应触发端口校验");
        assertEquals(1, callback.completeCount.get(), "无下游时应回到顶层收口");
    }

    // Test scenario: 批次已失效时不应触发出口缺失护栏，避免超时批次产生额外错误。
    @Test
    public void invalidBatchSkipsMissingExitGuard() {
        RuleChain chain = chainWithSource("n1", ctx -> {
        });
        RecordingCallback callback = new RecordingCallback();
        callback.valid = false;
        executor = newExecutor(chain, false);

        executor.dispatch(chain, "n1", message(), new ExecutionContext(callback));

        assertEquals(0, callback.errorCount.get(), "失效批次不应触发出口缺失");
        assertEquals(0, callback.completeCount.get());
    }

    // ---------- helpers ----------

    private ChainExecutor newExecutor(RuleChain chain, boolean strictPortCheck) {
        return ChainExecutor.builder()
                .expressionEngine(expressionEngine())
                .chainStore(storeFor(chain))
                .asyncExecutor(Runnable::run)
                .strictPortCheck(strictPortCheck)
                .build();
    }

    private static RuleChain chainWithSource(String nodeId, RuleNode node) {
        RuleChain chain = new RuleChain("c1", "test");
        chain.addNode(nodeId, "test", node, null);
        chain.addEntry(nodeId);
        return chain;
    }

    private static RuleMessage message() {
        return RuleMessage.newMessage()
                .id("m1")
                .type("T")
                .originator("o1")
                .payload("{}")
                .build();
    }

    private static IExpressionEngine expressionEngine() {
        return new IExpressionEngine() {
            @Override
            public boolean evaluate(String expr, ExecutionState state) {
                return true;
            }

            @Override
            public String transform(String expr, ExecutionState state) {
                return null;
            }

            @Override
            public Set<String> route(String expr, ExecutionState state) {
                return Collections.emptySet();
            }
        };
    }

    private static IChainStore storeFor(final RuleChain chain) {
        return new IChainStore() {
            @Override
            public RuleChain registerChain(Object chainData) {
                return chain;
            }

            @Override
            public RuleChain deleteChain(String chainId) {
                return null;
            }

            @Override
            public Object getChainRawData(String chainId) {
                return null;
            }

            @Override
            public RuleChain getChain(String chainId) {
                return chain.getId().equals(chainId) ? chain : null;
            }

            @Override
            public Collection<Object> getChains() {
                return Collections.emptyList();
            }
        };
    }

    private static final class RecordingCallback implements ExecutionCallback {

        private final AtomicInteger completeCount = new AtomicInteger();
        private final AtomicInteger errorCount = new AtomicInteger();
        private final AtomicReference<Throwable> lastError = new AtomicReference<Throwable>();
        private volatile boolean valid = true;

        @Override
        public void onComplete() {
            completeCount.incrementAndGet();
        }

        @Override
        public void onError(Throwable t) {
            errorCount.incrementAndGet();
            lastError.set(t);
        }

        @Override
        public boolean isValid() {
            return valid;
        }
    }
}