package io.fire.rule.core.chain;

import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.spi.IExpressionEngine;
import io.fire.rule.core.spi.IMessageSink;
import io.fire.rule.core.spi.NodeException;
import io.fire.rule.core.spi.RuleNode;
import io.fire.rule.core.store.IChainStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 回归：验证 {@link SampleNodeContext#enqueueNext} 在目标分区属于本实例
 * （{@link IMessageSink#isLocal} 为 true）时退化为本地 next，批次回调能正常收口。
 */
public class SampleNodeContextTest {

    private ChainExecutor executor;

    @AfterEach
    public void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    // Test scenario: isLocal=true 时应走本地路由、下游节点 ack 后回调收口，且不调用 sink.send。
    @Test
    public void localSinkRoutesToNextAndCompletesBatchCallback() {
        RuleChain chain = chainWithTarget("n1", "n2");
        RecordingSink sink = new RecordingSink(true);
        RecordingCallback callback = new RecordingCallback();
        ExecutionContext execCtx = new ExecutionContext(callback);
        SampleNodeContext ctx = context(chain, "n1", sink, callback, execCtx);

        ctx.emit(Collections.<String, Object>singletonMap("ok", Boolean.TRUE));
        ctx.enqueueNext("default");

        assertEquals(0, sink.sendCount.get(), "isLocal=true 时不应走跨节点 send");
        assertEquals(1, callback.completeCount.get(), "本地路由到下游节点后应正常收口");
        assertEquals(0, callback.errorCount.get(), "本地路由不应产生错误回调");
        assertNotNull(execCtx.lastMessage(), "路由后应记录最近流转的消息");
        assertNotNull(execCtx.lastMessage().getState().outputOf("n1"),
                "emit 的产出应写入命名空间并随状态下传");
    }

    // Test scenario: isLocal=false 时应交给 sink.send，携带正确路由信息，且本地不提前收口。
    @Test
    public void remoteSinkDelegatesAndDoesNotCompleteLocally() {
        RuleChain chain = chainWithTarget("n1", "n2");
        RecordingSink sink = new RecordingSink(false);
        RecordingCallback callback = new RecordingCallback();
        ExecutionContext execCtx = new ExecutionContext(callback);
        SampleNodeContext ctx = context(chain, "n1", sink, callback, execCtx);

        ctx.emit(Collections.<String, Object>singletonMap("ok", Boolean.TRUE));
        ctx.enqueueNext("default");

        assertEquals(1, sink.sendCount.get(), "isLocal=false 时应交给 sink.send");
        assertEquals("c1", sink.chainId, "应传递当前链 ID");
        assertEquals("n1", sink.nodeId, "应传递当前节点 ID");
        assertEquals(Collections.singleton("default"), sink.relations, "应传递端口集合");
        assertEquals("m1", sink.messageId, "应传递同一条消息");
        assertNotNull(sink.message.getState().outputOf("n1"), "跨节点消息应携带本节点产出");
        assertEquals(0, callback.completeCount.get(), "远程投递不应在本地收口");
    }

    // Test scenario: 批次已失效（isValid=false）时 enqueueNext 应为空操作。
    @Test
    public void invalidBatchMakesEnqueueNextNoOp() {
        RuleChain chain = chainWithTarget("n1", "n2");
        RecordingSink sink = new RecordingSink(true);
        RecordingCallback callback = new RecordingCallback();
        callback.valid = false;
        ExecutionContext execCtx = new ExecutionContext(callback);
        SampleNodeContext ctx = context(chain, "n1", sink, callback, execCtx);

        ctx.enqueueNext("default");

        assertEquals(0, sink.sendCount.get(), "失效批次不应发送消息");
        assertEquals(0, callback.completeCount.get(), "失效批次不应收口");
        assertEquals(0, callback.errorCount.get(), "失效批次不应报错");
    }

    // Test scenario: 本地路由但无下游时，应回跳至顶层并收口回调。
    @Test
    public void localRouteWithoutDownstreamClosesCallback() {
        RuleChain chain = new RuleChain("c1", "test");
        chain.addNode("n1", "end", ctx -> {
        }, null);
        chain.addEntry("n1");

        RecordingSink sink = new RecordingSink(true);
        RecordingCallback callback = new RecordingCallback();
        ExecutionContext execCtx = new ExecutionContext(callback);
        SampleNodeContext ctx = context(chain, "n1", sink, callback, execCtx);

        ctx.enqueueNext("default");

        assertEquals(0, sink.sendCount.get(), "本地无下游时不应走跨节点 send");
        assertEquals(1, callback.completeCount.get(), "无下游时应回到顶层并确认完成");
    }

    // ---------- helpers ----------

    private SampleNodeContext context(RuleChain chain, String nodeId, IMessageSink sink,
                                      RecordingCallback callback, ExecutionContext execCtx) {
        return contextWithCapabilities(chain, nodeId, sink, callback, execCtx,
                Collections.<Class<?>, Object>emptyMap());
    }

    private SampleNodeContext contextWithCapabilities(RuleChain chain, String nodeId, IMessageSink sink,
                                                      RecordingCallback callback, ExecutionContext execCtx,
                                                      Map<Class<?>, Object> capabilities) {
        executor = ChainExecutor.builder()
                .expressionEngine(expressionEngine())
                .chainStore(storeFor(chain))
                .asyncExecutor(Runnable::run)
                .build();

        RuleMessage msg = RuleMessage.newMessage()
                .id("m1")
                .type("T")
                .originator("o1")
                .payload("{\"k\":\"v\"}")
                .build();

        return SampleNodeContext.builder()
                .executor(executor)
                .chain(chain)
                .nodeId(nodeId)
                .msg(msg)
                .expressionEngine(expressionEngine())
                .asyncExecutor(Runnable::run)
                .sink(sink)
                .capabilities(capabilities)
                .execCtx(execCtx)
                .build();
    }

    private static RuleChain chainWithTarget(String source, String target) {
        RuleChain chain = new RuleChain("c1", "test");
        RuleNode sourceNode = ctx -> {
        };
        RuleNode targetNode = nodeCtx -> nodeCtx.ack();
        chain.addNode(source, "source", sourceNode, null);
        chain.addNode(target, "target", targetNode, null);
        chain.connect(source, "default", target);
        chain.addEntry(source);
        return chain;
    }

    private static RuleChain singleNodeChain(String nodeId) {
        RuleChain chain = new RuleChain("c1", "test");
        chain.addNode(nodeId, "single", ctx -> {
        }, null);
        chain.addEntry(nodeId);
        return chain;
    }

    private interface Marker {
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

    private static final class RecordingSink implements IMessageSink {

        private final boolean local;
        private final AtomicInteger sendCount = new AtomicInteger();
        private String chainId;
        private String nodeId;
        private Set<String> relations;
        private RuleMessage message;
        private String messageId;

        private RecordingSink(boolean local) {
            this.local = local;
        }

        @Override
        public boolean isLocal(String chainId, RuleMessage msg) {
            return local;
        }

        @Override
        public void send(String chainId, String nodeId, RuleMessage msg, Set<String> relations) {
            this.sendCount.incrementAndGet();
            this.chainId = chainId;
            this.nodeId = nodeId;
            this.relations = relations;
            this.message = msg;
            this.messageId = msg.getId();
        }
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

    // Test scenario: 未配置 sink 时应退化为本地路由并由下游节点收口。
    @Test
    public void nullSinkFallsBackToLocalRouting() {
        RuleChain chain = chainWithTarget("n1", "n2");
        RecordingCallback callback = new RecordingCallback();
        ExecutionContext execCtx = new ExecutionContext(callback);
        SampleNodeContext ctx = context(chain, "n1", null, callback, execCtx);

        ctx.enqueueNext("default");

        assertEquals(1, callback.completeCount.get(), "无 sink 时应本地路由并正常收口");
        assertEquals(0, callback.errorCount.get());
    }

    // Test scenario: 同一节点重复调用 next 必须抛 fatal NodeException。
    @Test
    public void repeatedNextThrowsFatalNodeException() {
        RuleChain chain = chainWithTarget("n1", "n2");
        RecordingCallback callback = new RecordingCallback();
        ExecutionContext execCtx = new ExecutionContext(callback);
        SampleNodeContext ctx = context(chain, "n1", new RecordingSink(true), callback, execCtx);

        ctx.next("default");

        NodeException error = assertThrows(NodeException.class, () -> ctx.next("default"));
        assertTrue(error.isFatal(), "重复出口必须标记为 fatal");
        assertTrue(error.getMessage().contains("重复产生出口"), "错误信息应说明重复出口");
    }

    // Test scenario: next 之后再调用 fail 同样属于重复出口，必须抛 fatal NodeException。
    @Test
    public void failAfterNextThrowsFatalNodeException() {
        RuleChain chain = chainWithTarget("n1", "n2");
        RecordingCallback callback = new RecordingCallback();
        ExecutionContext execCtx = new ExecutionContext(callback);
        SampleNodeContext ctx = context(chain, "n1", new RecordingSink(true), callback, execCtx);

        ctx.next("default");

        NodeException error = assertThrows(NodeException.class,
                () -> ctx.fail(new RuntimeException("boom")));
        assertTrue(error.isFatal(), "重复出口必须标记为 fatal");
        assertTrue(error.getMessage().contains("重复产生出口"));
    }

    // Test scenario: 重复调用 ack 必须抛 fatal NodeException。
    @Test
    public void repeatedAckThrowsFatalNodeException() {
        RuleChain chain = singleNodeChain("n1");
        RecordingCallback callback = new RecordingCallback();
        ExecutionContext execCtx = new ExecutionContext(callback);
        SampleNodeContext ctx = context(chain, "n1", new RecordingSink(true), callback, execCtx);

        ctx.ack();

        NodeException error = assertThrows(NodeException.class, ctx::ack);
        assertTrue(error.isFatal());
        assertEquals(1, callback.completeCount.get(), "第一次 ack 应正常收口");
    }

    // Test scenario: 正常单次 ack 出口不应抛异常。
    @Test
    public void singleAckExitDoesNotThrow() {
        RuleChain chain = singleNodeChain("n1");
        RecordingCallback callback = new RecordingCallback();
        ExecutionContext execCtx = new ExecutionContext(callback);
        SampleNodeContext ctx = context(chain, "n1", new RecordingSink(true), callback, execCtx);

        ctx.ack();

        assertEquals(1, callback.completeCount.get());
        assertEquals(0, callback.errorCount.get());
    }

    // Test scenario: 正常单次 next 出口（无下游回跳）不应抛异常。
    @Test
    public void singleNextExitDoesNotThrow() {
        RuleChain chain = singleNodeChain("n1");
        RecordingCallback callback = new RecordingCallback();
        ExecutionContext execCtx = new ExecutionContext(callback);
        SampleNodeContext ctx = context(chain, "n1", new RecordingSink(true), callback, execCtx);

        ctx.next("default");

        assertEquals(1, callback.completeCount.get());
        assertEquals(0, callback.errorCount.get());
    }

    // Test scenario: requireCapability 在能力未注册时抛 fatal NodeException。
    @Test
    public void requireCapabilityThrowsFatalWhenMissing() {
        RuleChain chain = singleNodeChain("n1");
        RecordingCallback callback = new RecordingCallback();
        ExecutionContext execCtx = new ExecutionContext(callback);
        SampleNodeContext ctx = contextWithCapabilities(chain, "n1", new RecordingSink(true), callback, execCtx,
                Collections.<Class<?>, Object>emptyMap());

        NodeException error = assertThrows(NodeException.class,
                () -> ctx.requireCapability(Marker.class));
        assertTrue(error.isFatal(), "缺少必需能力应为 fatal");
        assertTrue(error.getMessage().contains("缺少必需能力"));
    }

    // Test scenario: requireCapability 在能力已注册时返回同一实例。
    @Test
    public void requireCapabilityReturnsRegisteredInstance() {
        Marker impl = new Marker() {
        };
        Map<Class<?>, Object> capabilities = new HashMap<Class<?>, Object>();
        capabilities.put(Marker.class, impl);
        RuleChain chain = singleNodeChain("n1");
        RecordingCallback callback = new RecordingCallback();
        ExecutionContext execCtx = new ExecutionContext(callback);
        SampleNodeContext ctx = contextWithCapabilities(chain, "n1", new RecordingSink(true), callback, execCtx,
                capabilities);

        assertSame(impl, ctx.requireCapability(Marker.class));
    }

    // Test scenario: 批次失效（isValid=false）时 fail 不派发异常端口，也不触发回调。
    @Test
    public void failOnInvalidBatchDoesNotDispatchOrCallback() {
        RuleChain chain = chainWithTarget("n1", "n2");
        chain.connect("n1", "exception", "n2");
        RecordingCallback callback = new RecordingCallback();
        callback.valid = false;
        ExecutionContext execCtx = new ExecutionContext(callback);
        SampleNodeContext ctx = context(chain, "n1", new RecordingSink(true), callback, execCtx);

        ctx.fail(new RuntimeException("boom"));

        assertEquals(0, callback.errorCount.get(), "失效批次不应回调 onError");
        assertEquals(0, callback.completeCount.get(), "失效批次不应回调 onComplete");
        assertEquals(SampleNodeContext.RouteState.NONE, ctx.routeState(), "失效批次不应记录出口");
    }

    // Test scenario: 当前 (chain,node) 已在处理栈中时，input 应走 fatal 而非继续嵌套。
    @Test
    public void inputDetectsCycleAndFailsFatally() {
        RuleChain chain = singleNodeChain("n1");
        RecordingCallback callback = new RecordingCallback();
        ExecutionContext execCtx = new ExecutionContext(callback);
        execCtx.pushToStack("c1", "n1");
        SampleNodeContext ctx = context(chain, "n1", new RecordingSink(true), callback, execCtx);

        ctx.input("c1");

        assertEquals(1, callback.errorCount.get(), "循环引用应回调 onError");
        Throwable error = callback.lastError.get();
        assertTrue(error instanceof NodeException, "循环引用应抛 NodeException");
        assertTrue(((NodeException) error).isFatal(), "循环引用应为 fatal");
        assertTrue(error.getMessage().contains("循环引用"));
        assertEquals(1, execCtx.getStack().depth(), "循环检测不应继续压栈");
        assertEquals(SampleNodeContext.RouteState.FAILED, ctx.routeState());
    }
}