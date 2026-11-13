package io.fire.rule.spring.flowgram.execution;

import io.fire.rule.core.RuleEngine;
import io.fire.rule.core.chain.ChainExecutor;
import io.fire.rule.core.chain.ExecutionContext;
import io.fire.rule.core.chain.RuleChain;
import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.spi.NodeException;
import io.fire.rule.spring.flowgram.ExecutionTrace;
import io.fire.rule.spring.flowgram.NodeProgressListener;
import io.fire.rule.spring.flowgram.TracingEngineObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FlowGram 运行时执行器：把一次画布执行包装成“独立 ChainExecutor + 独立 tracer”的同步调用。
 *
 * <p>每次执行都从 {@link ChainExecutor.Builder} 构建一个临时执行器，执行完立即 shutdown；
 * 因此运行期任务可以并发执行，不再用全局锁串行化，也不会污染生产观测器或死信。
 *
 * <p>不挂 MessageSink，跨节点投递退化为本地投递，执行不会真的进入消息队列。
 *
 * @author Cai FeiYue
 * @date 2026-10-26
 */
public class FlowGramExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(FlowGramExecutionService.class);

    private static final long DEFAULT_TIMEOUT_MS = 8000L;

    private static final long MAX_TIMEOUT_MS = 60000L;

    private final ChainExecutor.Builder chainExecutorBuilder;

    private final Map<Class<?>, Object> capabilities;

    private final AtomicInteger runSeq = new AtomicInteger();

    public FlowGramExecutionService(ChainExecutor.Builder chainExecutorBuilder,
                                    ObjectProvider<RuleEngine.Builder> builderProvider) {
        RuleEngine.Builder engineBuilder = builderProvider.getIfAvailable();
        this.capabilities = engineBuilder == null
                ? new HashMap<>()
                : new HashMap<>(engineBuilder.capabilities());
        this.chainExecutorBuilder = chainExecutorBuilder;
        chainExecutorBuilder.capabilities(capabilities);
    }

    public <T> void capability(Class<T> type, T impl) {
        capabilities.put(type, impl);
        chainExecutorBuilder.capability(type, impl);
    }

    /**
     * 执行一次试跑。
     *
     * @param chainJson 画布导出的链定义；为空时按 chainId 使用已保存的链
     * @param chainId   目标链 ID（为空时由 chainJson 决定）
     * @param payload   报文内容（JSON 字符串）
     * @param metadata  附加元数据，可为空
     * @param timeoutMs 超时毫秒数，非正数取默认值
     */
    public ExecutionTrace execute(String chainJson, String chainId, String payload,
                                  Map<String, Object> metadata, long timeoutMs) {
        return execute(chainJson, chainId, payload, metadata, timeoutMs, NodeProgressListener.NOOP);
    }

    /**
     * 执行一次试跑，并把逐节点进度透出给 {@code listener}。
     */
    public ExecutionTrace execute(String chainJson, String chainId, String payload,
                                  Map<String, Object> metadata, long timeoutMs,
                                  NodeProgressListener listener) {
        long timeout = timeoutMs > 0 ? Math.min(timeoutMs, MAX_TIMEOUT_MS) : DEFAULT_TIMEOUT_MS;
        ChainExecutor executor = newExecutor();
        try {
            RuleChain chain = resolveChain(executor, chainJson, chainId);
            return run(executor, chain, payload, metadata, timeout, listener);
        } finally {
            executor.shutdown();
        }
    }

    private synchronized ChainExecutor newExecutor() {
        // Builder 的 build() 在首次调用时会补默认 asyncExecutor，做一次轻量互斥避免并发初始化
        return chainExecutorBuilder.build();
    }

    private ExecutionTrace run(ChainExecutor executor, RuleChain chain, String payload,
                               Map<String, Object> metadata, long timeout,
                               NodeProgressListener listener) {
        long runStart = System.nanoTime();
        TracingEngineObserver tracer = new TracingEngineObserver(executor.chainStore(), listener);
        ExecutionTrace trace = tracer.trace();
        trace.setRunId("run-" + runStart + "-" + runSeq.incrementAndGet());
        trace.setChainId(chain.getId());
        trace.setChainName(chain.getName());
        trace.setStartedAt(System.currentTimeMillis());

        RuleMessage message = RuleMessage.newMessage()
                .type("FLOWGRAM_RUN")
                .originator("flowgram")
                .payload(payload == null || payload.trim().isEmpty() ? "{}" : payload)
                .metaData(metadata)
                .build();
        trace.setMessageId(message.getId());

        RunCallback callback = new RunCallback();
        ExecutionContext execCtx = new ExecutionContext(callback);
        // 临时执行器只服务本次运行，直接替换 observer 即可，不需要恢复全局状态
        executor.replaceObserver(tracer);
        try {
            executor.execute(message, chain.getId(), execCtx);
            if (!callback.await(timeout)) {
                trace.setStatus(ExecutionTrace.STATUS_TIMEOUT);
                trace.setError("执行超时（" + timeout + "ms），未完成的分支已放弃");
            } else if (callback.error() != null) {
                trace.setStatus(ExecutionTrace.STATUS_FAILED);
                trace.setError(describe(callback.error()));
            } else {
                trace.setStatus(ExecutionTrace.STATUS_COMPLETED);
            }
        } catch (RuntimeException e) {
            // 节点异常会被 ChainExecutor 捕获，这里兜住装配 / 路由层的问题
            trace.setStatus(ExecutionTrace.STATUS_FAILED);
            trace.setError(describe(e));
        } finally {
            trace.setDurationMs(roundMs(System.nanoTime() - runStart));
            RuleMessage last = execCtx.lastMessage();
            Map<String, Object> finalMeta = last != null
                    ? last.getState().meta() : message.getState().meta();
            trace.setMetadata(new LinkedHashMap<>(finalMeta));
            tracer.finish(trace.getStatus(), trace.getError());
        }
        LOG.debug("FlowGram 执行完成: chain={} status={} nodes={} cost={}ms",
                chain.getId(), trace.getStatus(), trace.getNodes().size(), trace.getDurationMs());
        return trace;
    }

    private RuleChain resolveChain(ChainExecutor executor, String chainJson, String chainId) {
        if (chainJson != null && !chainJson.trim().isEmpty()) {
            try {
                return executor.chainStore().registerChain(chainJson);
            } catch (NodeException e) {
                throw new IllegalArgumentException("规则链无法加载：" + e.getMessage(), e);
            }
        }
        if (chainId == null || chainId.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 chainId 或 chain 定义");
        }
        RuleChain chain = executor.chainStore().getChain(chainId.trim());
        if (chain == null) {
            throw new IllegalArgumentException("规则链未注册：" + chainId);
        }
        return chain;
    }

    private static double roundMs(long nanos) {
        return Math.round(nanos / 1000d) / 1000d;
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}