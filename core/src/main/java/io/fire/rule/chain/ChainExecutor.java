package io.fire.rule.chain;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.observability.EngineObserver;
import io.fire.rule.spi.*;
import io.fire.rule.store.IChainStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 链路执行器：驱动消息在 DAG 上流动。
 *
 * <p>职责边界很清晰——它只管"下一步发给谁"，
 * 不管批次、重试、分区（那些在运行层），也不管节点内部逻辑（在节点层）。
 */
public class ChainExecutor {

    private final IChainStore chainStore;
    private final IExpressionEngine expressionEngine;
    private final Executor asyncExecutor;
    private volatile IMessageSink sink;
    private final Map<Class<?>, Object> capabilities = new HashMap<>();
    private final EngineObserver observer;
    private final ScheduledExecutorService scheduler;

    public ChainExecutor(Builder b) {
        this.chainStore = b.chainStore;
        this.expressionEngine = b.expressionEngine;
        this.asyncExecutor = b.asyncExecutor;
        this.sink = b.sink;
        this.observer = b.observer != null ? b.observer : EngineObserver.NOOP;
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "rule-self-scheduler");
            t.setDaemon(true);
            return t;
        });
        if (b.capabilities != null) {
            this.capabilities.putAll(b.capabilities);
        }
    }

    /**
     * 后注入消息投递器。
     *
     * <p>存在循环依赖：DistributedMessageSink 需要 ChainExecutor 做本地路由，
     * 而 ChainExecutor 又需要 MessageSink。因此先无 sink 构造，再 setter 注入。
     */
    public void setMessageSink(IMessageSink sink) {
        this.sink = sink;
    }

    public IMessageSink messageSink() {
        return sink;
    }

    public RuleChain register(Object chain) throws NodeException {
        return chainStore.registerChain(chain);
    }

    public RuleChain getChain(String chainId) {
        return chainStore.getChain(chainId);
    }

    public void execute(RuleMessage msg, String chainId, ExecutionContext ctx) {
        execute(msg, chainId, null, ctx);
    }

    /**
     * 入口：按执行上下文所处位置决定从哪开始。
     */
    public void execute(RuleMessage msg, String chainId, String nodeId, ExecutionContext execCtx) {
        if (chainId == null) {
            execCtx.getCallback().onError(new IllegalStateException("未指定规则链 ID"));
            return;
        }
        RuleChain chain = chainStore.getChain(chainId);
        if (chain == null) {
            execCtx.getCallback().onError(new IllegalStateException("未注册的规则链: " + chainId));
            return;
        }

        // 从队列恢复，继续原节点
        if (nodeId != null && chain.hasNode(nodeId)) {
            dispatch(chain, nodeId, msg, execCtx);
            return;
        }

        List<String> nodeIds = chain.entryNodes();
        if (nodeIds.isEmpty()) {
            execCtx.getCallback().onComplete();
            return;
        }
        for (String id : nodeIds) {
            dispatch(chain, id, msg, execCtx);
        }
    }

    /**
     * 跨节点恢复：从指定节点按指定关系继续路由。
     *
     * <p><b>与 dispatch 的本质区别</b>：不执行 nodeId 本身，
     * 只模拟"该节点调用了 tellNext(msg, relations)"的效果。
     *
     * <p>为什么必须有这个方法：
     * 消息在实例 A 的节点 X 上决定走 "有隐患" 分支，
     * 序列化后投递到实例 B。B 如果重新执行 X，会造成重复处理；
     * 正确做法是直接从 X 的 "有隐患" 关系继续往下走。
     */
    public void resume(RuleMessage msg, String chainId, String nodeId, Set<String> relations, ExecutionContext execCtx) {
        RuleChain chain = chainStore.getChain(chainId);
        if (chain == null) {
            execCtx.getCallback().onError(new IllegalStateException("未注册的规则链: " + chainId));
            return;
        }
        if (!chain.hasNode(nodeId)) {
            execCtx.getCallback().onError(new IllegalStateException(
                    "规则链 " + chainId + " 中不存在节点: " + nodeId));
            return;
        }
        // 复用 DefaultNodeContext 的路由逻辑：它持有 nodeId，查邻接表即可
        INodeContext ctx = DefaultNodeContext.builder()
                .executor(this)
                .chain(chain)
                .nodeId(nodeId)
                .expressionEngine(expressionEngine)
                .asyncExecutor(asyncExecutor)
                .sink(sink)
                .capabilities(capabilities)
                .execCtx(execCtx)
                .build();
        ctx.tellNext(msg, relations);
    }

    /**
     * 把消息交给指定节点处理。
     */
    public void dispatch(RuleChain chain, String nodeId, RuleMessage msg, ExecutionContext execCtx) {
        RuleNode node = chain.node(nodeId);
        if (node == null) {
            execCtx.getCallback().onComplete();
            return;
        }

        INodeContext ctx = DefaultNodeContext.builder()
                .executor(this)
                .chain(chain)
                .nodeId(nodeId)
                .expressionEngine(expressionEngine)
                .asyncExecutor(asyncExecutor)
                .sink(sink)
                .capabilities(capabilities)
                .execCtx(execCtx)
                .build();

        long start = System.nanoTime();
        try {
            node.onMsg(ctx, msg);
        } catch (Exception e) {
            observer.onNodeError(chain.getId(), nodeId, e);
            execCtx.getCallback().onError(e);
            return;
        }

        observer.onNodeFinished(chain.getId(), nodeId, System.nanoTime() - start);
    }

    /**
     * tellSelf：延迟投递给自己，用于周期任务 / 超时检查。
     */
    public void scheduleSelf(RuleChain chain, String nodeId,
                             RuleMessage msg, ExecutionContext execCtx, long delayMs) {
        scheduler.schedule(() -> dispatch(chain, nodeId, msg, execCtx), delayMs, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private IExpressionEngine expressionEngine;
        private IChainStore chainStore;
        private Executor asyncExecutor;
        private IMessageSink sink;
        private EngineObserver observer;
        private Map<Class<?>, Object> capabilities;

        public Builder chainStore(IChainStore chainStore) {
            this.chainStore = chainStore;
            return this;
        }

        public Builder expressionEngine(IExpressionEngine expressionEngine) {
            this.expressionEngine = expressionEngine;
            return this;
        }

        public Builder asyncExecutor(Executor asyncExecutor) {
            this.asyncExecutor = asyncExecutor;
            return this;
        }

        public Builder sink(IMessageSink sink) {
            this.sink = sink;
            return this;
        }

        public Builder observer(EngineObserver observer) {
            this.observer = observer;
            return this;
        }

        /**
         * 注册业务能力，节点通过 ctx.capability(Xxx.class) 获取。
         */
        public <T> Builder capability(Class<T> type, T impl) {
            if (capabilities == null) {
                capabilities = new HashMap<Class<?>, Object>();
            }
            capabilities.put(type, impl);
            return this;
        }

        /**
         * 批量注册业务能力。
         */
        public Builder capabilities(Map<Class<?>, Object> map) {
            if (map == null) {
                return this;
            }
            if (capabilities == null) {
                capabilities = new HashMap<>();
            }
            capabilities.putAll(map);
            return this;
        }

        public ChainExecutor build() {
            if (expressionEngine == null) {
                throw new IllegalStateException("ExpressionEngine 必填");
            }
            if (asyncExecutor == null) {
                throw new IllegalStateException("asyncExecutor 必填");
            }
            return new ChainExecutor(this);
        }
    }
}
