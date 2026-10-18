package io.fire.rule.runtime;

import io.fire.rule.chain.ChainExecutor;
import io.fire.rule.chain.ExecutionCallback;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.observability.EngineObserver;
import io.fire.rule.runtime.dispatcher.KeyOrderedDispatcher;
import io.fire.rule.runtime.dispatcher.TaskDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 规则引擎主循环——整个框架的调度心脏。
 *
 * <p>流程（与 ThingsBoard 的 TbRuleEngineQueueConsumerManager 同构，但去掉了 Actor 与 protobuf）：
 * <pre>
 *   poll 一批 → 建批次上下文 → 按提交策略投递
 *            → await(批次超时) → 汇总 pending/success/failed
 *            → 重试策略判决 → commit 或只重投失败项
 * </pre>
 *
 * <p><b>为什么必须有批次级超时</b>：
 * 单个节点调外部 HTTP 卡死，不能让整批消息陪着等。
 * 批次超时保证"个别慢消息不拖垮整批"，这正是它独立于提交策略配置的原因。
 */
public class PackProcessor {

    private final MessageSource source;
    private final ChainExecutor chainExecutor;
    private final SubmitStrategy submitStrategy;
    private final RetryStrategy retryStrategy;
    private final TaskDispatcher dispatcher;
    private final Executor asyncExecutor;
    private final EngineObserver observer;

    private final int pollSize;
    private final long pollIntervalMs;
    private final long packTimeoutMs;

    private volatile boolean running;
    private volatile boolean started;

    public PackProcessor(Builder b) {
        this.source = b.source;
        this.chainExecutor = b.chainExecutor;
        this.submitStrategy = b.submitStrategy;
        this.retryStrategy = b.retryStrategy;
        this.dispatcher = b.dispatcher;
        this.asyncExecutor = b.asyncExecutor;
        this.observer = b.observer != null ? b.observer : EngineObserver.NOOP;
        this.pollSize = b.pollSize;
        this.pollIntervalMs = b.pollIntervalMs;
        this.packTimeoutMs = b.packTimeoutMs;
    }

    /** 启动消费循环（阻塞，通常在独立线程中运行）。 */
    public void run() {
        running = true;
        started = true;
        while (running) {
            List<Envelope> msgs = source.poll(pollSize);
            if (msgs == null || msgs.isEmpty()) {
                sleepQuietly(pollIntervalMs);
                continue;
            }
            processPack(msgs);
        }
    }

    public void stop() {
        running = false;
    }

    /**
     * 处理一批消息，暴露出来便于单元测试直接调用。
     *
     * <p>注意：这里用 do-while 而非 while(running)。
     * 同步调用（submitSync）时 running 仍为 false，
     * 若用 while(running) 会直接跳过整批处理——这是个隐蔽的坑。
     */
    public void processPack(List<Envelope> msgs) {
        submitStrategy.init(msgs);

        do {
            PackContext packCtx = new PackContext(source.name(), submitStrategy);
            List<Envelope> submitted = new ArrayList<>();

            submitStrategy.submitAttempt((id, msg) -> submitOne(packCtx, id, msg));

            boolean timeout = false;
            try {
                timeout = !packCtx.await(packTimeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                packCtx.cancel();
                return;
            }
            if (timeout) {
                observer.onPackTimeout(source.name(), packCtx.pendingCount());
            }
            if (!packCtx.getFailedMap().isEmpty()) {
                observer.onPackFailed(source.name(), packCtx.getFailedMap().size(),
                        packCtx.failureSummary(3));
            }

            ProcessingDecision decision = retryStrategy.analyze(packCtx);
            observer.onPackFinished(source.name(), msgs.size(), decision.isCommit());

            packCtx.cleanup();

            if (decision.isCommit()) {
                submitStrategy.stop();
                source.commit();
                return;
            }
            // 引擎已停止则放弃重试；否则重试次数完全由 RetryStrategy 控制
            if (started && !running) {
                submitStrategy.stop();
                return;
            }
            submitStrategy.update(decision.getReprocessMap());
        } while (true);
    }

    private void submitOne(final PackContext packCtx, final String id, Envelope envelope) {
        // 每条消息带上批次回调，节点的 ack / failure 才能回传
        final RuleMessage msg = envelope.getMessage()
                .copy()
                .callback(packCtx.callbackFor(id))
                .build();

        // 按 originator 分区：同一设备串行，不同设备并行
        dispatcher.execute(msg.getOriginator() == null ? "default" : msg.getOriginator(),
                () -> {
                    try {
                        // 跨节点恢复的消息带 relations，走 resume 而非 execute
                        Set<String> rels = envelope.getRelations();
                        if (rels != null && !rels.isEmpty()
                                && msg.getNodeId() != null && msg.getChainId() != null) {
                            chainExecutor.resume(msg.getChainId(), msg.getNodeId(),
                                    msg, rels, new ExecutionCallback() {
                                        @Override
                                        public void onComplete() {
                                            packCtx.onSuccess(id);
                                        }

                                        @Override
                                        public void onError(Throwable t) {
                                            packCtx.onFailure(id, t);
                                        }
                                    });
                            return;
                        }
                        chainExecutor.execute(msg, new ExecutionCallback() {
                            @Override
                            public void onComplete() {
                                packCtx.onSuccess(id);
                            }

                            @Override
                            public void onError(Throwable t) {
                                packCtx.onFailure(id, t);
                            }
                        });
                    } catch (Exception e) {
                        packCtx.onFailure(id, e);
                    }
                });
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private MessageSource source;
        private ChainExecutor chainExecutor;
        private SubmitStrategy submitStrategy = new BurstSubmitStrategy();
        private RetryStrategy retryStrategy = RetryStrategy.retryFailed();
        private TaskDispatcher dispatcher = KeyOrderedDispatcher.defaultDispatcher();
        private Executor asyncExecutor;
        private EngineObserver observer;
        private int pollSize = 256;
        private long pollIntervalMs = 25L;
        private long packTimeoutMs = 2000L;

        public Builder source(MessageSource source) {
            this.source = source;
            return this;
        }

        public Builder chainExecutor(ChainExecutor chainExecutor) {
            this.chainExecutor = chainExecutor;
            return this;
        }

        public Builder submitStrategy(SubmitStrategy submitStrategy) {
            this.submitStrategy = submitStrategy;
            return this;
        }

        public Builder retryStrategy(RetryStrategy retryStrategy) {
            this.retryStrategy = retryStrategy;
            return this;
        }

        public Builder dispatcher(TaskDispatcher dispatcher) {
            this.dispatcher = dispatcher;
            return this;
        }

        public Builder asyncExecutor(Executor asyncExecutor) {
            this.asyncExecutor = asyncExecutor;
            return this;
        }

        public Builder observer(EngineObserver observer) {
            this.observer = observer;
            return this;
        }

        public Builder pollSize(int pollSize) {
            this.pollSize = pollSize;
            return this;
        }

        public Builder pollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
            return this;
        }

        /** 批次处理超时，默认 2 秒；高优先级队列可设更小。 */
        public Builder packTimeoutMs(long packTimeoutMs) {
            this.packTimeoutMs = packTimeoutMs;
            return this;
        }

        public PackProcessor build() {
            if (source == null) {
                throw new IllegalStateException("MessageSource 必填");
            }
            if (chainExecutor == null) {
                throw new IllegalStateException("ChainExecutor 必填");
            }
            if (asyncExecutor == null) {
                throw new IllegalStateException("asyncExecutor 必填（耗时 I/O 必须外置线程池）");
            }
            return new PackProcessor(this);
        }
    }
}
