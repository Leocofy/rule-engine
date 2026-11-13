package io.fire.rule.core.chain;

import io.fire.rule.core.config.INodeConfigBinder;
import io.fire.rule.core.config.IValueResolver;
import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.observability.EngineObserver;
import io.fire.rule.core.observability.NodeDelta;
import io.fire.rule.core.spi.*;
import io.fire.rule.core.store.IChainStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 链路执行器：驱动消息在规则链 DAG 上流动。
 *
 * <p>职责边界很清晰——它只管"下一步从哪个端口发给谁"，
 * 不管批次、重试、分区（那些在运行层），也不管节点内部逻辑（在节点层）。
 * 所有节点（含内置控制流 start/end/condition/loop）一律以普通 {@link RuleNode} 执行，
 * 本类不含任何特定节点类型的分支。
 */
public class ChainExecutor {

    private final IValueResolver valueResolver;
    private final INodeConfigBinder configBinder;
    private final IChainStore chainStore;
    private final IExpressionEngine expressionEngine;
    private final Executor asyncExecutor;
    private volatile IMessageSink sink;
    private final Map<Class<?>, Object> capabilities = new HashMap<>();
    private volatile EngineObserver observer;
    private final ScheduledExecutorService scheduler;
    /**
     * 是否严格校验节点使用的端口；默认关闭，开启后未声明端口直接 fatal。
     */
    private final boolean strictPortCheck;

    public ChainExecutor(Builder b) {
        this.valueResolver = b.valueResolver;
        this.configBinder = b.configBinder;
        this.chainStore = b.chainStore;
        this.expressionEngine = b.expressionEngine;
        this.asyncExecutor = b.asyncExecutor;
        this.sink = b.sink;
        this.observer = b.observer != null ? b.observer : new EngineObserver.Stats();
        this.strictPortCheck = b.strictPortCheck;
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

    public IChainStore chainStore() {
        return chainStore;
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
     * 跨节点恢复：从指定节点按指定端口继续路由。
     *
     * <p><b>与 dispatch 的本质区别</b>：不执行 nodeId 本身，
     * 只模拟"该节点调用了 tellNext(msg, ports)"的效果。
     */
    public void resume(RuleMessage msg, String chainId, String nodeId, Set<String> ports, ExecutionContext execCtx) {
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
        INodeContext ctx = buildContext(chain, nodeId, msg, execCtx);
        ctx.next(ports);
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
        execCtx.recordMessage(msg);

        long start = System.nanoTime();

        Object inputs = null;
        try {
            SampleNodeContext ctx = buildContext(chain, nodeId, msg, execCtx);
            observer.onNodeStarted(NodeDelta.started(chain.getId(), nodeId));
            node.onMsg(ctx);
            inputs = ctx.inputs();
            Object produced = ctx.emittedOutput();
            if (ctx.isValid() && !ctx.asyncDelegated()
                    && ctx.routeState() == SampleNodeContext.RouteState.NONE) {
                throw new NodeException("节点 " + nodeId
                        + " 执行结束但未产生任何出口（next/fail/ack/input/output/retrySelf/enqueueNext），"
                        + "消息将无法继续流转", true);
            }

            observer.onNodeFinished(NodeDelta.finished(chain.getId(), nodeId, inputs, produced, System.nanoTime() - start));
        } catch (Exception e) {
            observer.onNodeError(chain.getId(), nodeId, inputs, e);
            execCtx.getCallback().onError(e);
        }
    }

    /**
     * 临时替换观测器，返回旧值供调用方恢复。
     *
     * <p>试跑需要本次专属 tracer，不能污染全局统计；由调用方串行化使用并在 finally 中恢复。
     */
    public void replaceObserver(EngineObserver newObserver) {
        if (newObserver != null) {
            this.observer = newObserver;
        }
    }

    private SampleNodeContext buildContext(RuleChain chain, String nodeId, RuleMessage msg, ExecutionContext execCtx) {
        return SampleNodeContext.builder()
                .executor(this)
                .chain(chain)
                .nodeId(nodeId)
                .msg(msg)
                .expressionEngine(expressionEngine)
                .asyncExecutor(asyncExecutor)
                .sink(sink)
                .capabilities(capabilities)
                .execCtx(execCtx)
                .valueResolver(valueResolver)
                .configBinder(configBinder)
                .strictPortCheck(strictPortCheck)
                .build();
    }

    /**
     * 同步运行一条已注册子链（通用编排原语，不含循环语义）：以 {@code seedMsg} 的状态为入口，
     * 在独立子上下文中顺序跑完所有入口节点，返回运行结束后的<b>累积状态快照</b>。
     *
     * <p>子链不存在或无入口时原样返回 {@code seedMsg} 的状态。由 {@link SampleNodeContext#runSubChain}
     * 委托调用，供需要"驱动一段子图并拿回终态"的节点（如 Loop）使用。
     */
    public ExecutionState runSubChain(String chainId, RuleMessage seedMsg) {
        ExecutionState seed = seedMsg.getState();
        RuleChain sub = chainStore.getChain(chainId);
        if (sub == null || sub.entryNodes().isEmpty()) {
            return seed;
        }
        ExecutionContext child = new ExecutionContext(ExecutionCallback.NOOP);
        for (String entry : sub.entryNodes()) {
            dispatch(sub, entry, seedMsg, child);
        }
        return child.lastMessage() != null ? child.lastMessage().getState() : seed;
    }

    /**
     * 节点完成路由后转发给观测器，供执行追踪记录"走了哪个端口分支"。
     *
     * <p>由 {@link SampleNodeContext} 在真正派发下游之前调用，
     * 保证时间线上"路由"先于"下游执行"。
     */
    public void notifyRouted(RuleChain chain, String nodeId, RuleMessage msg,
                             Set<String> ports, List<String> targets) {
        Object output = msg.getState().outputOf(nodeId);
        observer.onNodeRouted(NodeDelta.routed(chain.getId(), nodeId, output, ports, targets));
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
        private IValueResolver valueResolver;
        private INodeConfigBinder configBinder;
        private boolean strictPortCheck;

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
                capabilities = new HashMap<>();
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

        public Builder valueResolver(IValueResolver valueResolver) {
            this.valueResolver = valueResolver;
            return this;
        }

        public Builder configBinder(INodeConfigBinder configBinder) {
            this.configBinder = configBinder;
            return this;
        }

        /**
         * 开启后，节点使用未在规则链该节点出边中声明的端口会直接抛 fatal，
         * 避免端口拼错导致消息静默终止。默认关闭以兼容动态端口节点。
         */
        public Builder strictPortCheck(boolean strictPortCheck) {
            this.strictPortCheck = strictPortCheck;
            return this;
        }

        public ChainExecutor build() {
            if (expressionEngine == null) {
                throw new IllegalStateException("ExpressionEngine 必填");
            }
            if (asyncExecutor == null) {
                asyncExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r);
                    t.setName("chain-executor");
                    t.setDaemon(true);
                    return t;
                });
            }
            if (valueResolver == null) {
                throw new IllegalStateException("ValueResolver No Impl");
            }
            return new ChainExecutor(this);
        }
    }
}
