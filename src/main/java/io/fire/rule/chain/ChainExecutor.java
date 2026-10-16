package io.fire.rule.chain;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.observability.EngineObserver;
import io.fire.rule.spi.IExpressionEngine;
import io.fire.rule.spi.IMessageSink;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.RuleNode;

import java.util.HashMap;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final Map<String, RuleChain> chains = new LinkedHashMap<>();
    private final IExpressionEngine expressionEngine;
    private final Executor asyncExecutor;
    private volatile IMessageSink sink;
    private final Map<Class<?>, Object> capabilities = new HashMap<>();
    private final EngineObserver observer;
    private final ScheduledExecutorService scheduler;
    private final String defaultChainId;

    public ChainExecutor(Builder b) {
        this.expressionEngine = b.expressionEngine;
        this.asyncExecutor = b.asyncExecutor;
        this.sink = b.sink;
        this.observer = b.observer != null ? b.observer : EngineObserver.NOOP;
        this.defaultChainId = b.defaultChainId;
        this.sink = b.sink;
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

    public void register(RuleChain chain) {
        chains.put(chain.getId(), chain);
    }

    public RuleChain chain(String chainId) {
        return chains.get(chainId);
    }

    /** 入口：按消息当前所处位置决定从哪开始。 */
    public void execute(RuleMessage msg, ExecutionCallback callback) {
        String chainId = msg.getChainId();
        if (chainId == null) {
            // 消息未指定目标链时走默认链——避免"消息进来了却静默丢弃"
            chainId = defaultChainId != null ? defaultChainId
                    : (chains.size() == 1 ? chains.keySet().iterator().next() : null);
        }
        if (chainId == null) {
            callback.onError(new IllegalStateException(
                    "消息未指定 chainId，且引擎未配置默认链（已注册: " + chains.keySet() + "）"));
            return;
        }
        executeInternal(msg.copy().chainId(chainId).build(), callback);
    }

    private void executeInternal(RuleMessage msg, ExecutionCallback callback) {
        RuleChain chain = chains.get(msg.getChainId());
        if (chain == null) {
            callback.onError(new IllegalStateException("未注册的规则链: " + msg.getChainId()));
            return;
        }
        String nodeId = msg.getNodeId();
        if (nodeId != null && chain.hasNode(nodeId)) {
            dispatch(chain, nodeId, msg, callback);   // 从队列恢复，继续原节点
            return;
        }
        List<String> entries = chain.entryNodes();
        if (entries.isEmpty()) {
            callback.onComplete();
            return;
        }
        for (String entry : entries) {
            dispatch(chain, entry, msg, callback);
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
    public void resume(String chainId, String nodeId, RuleMessage msg,
                       Set<String> relations, ExecutionCallback callback) {
        RuleChain chain = chains.get(chainId);
        if (chain == null) {
            callback.onError(new IllegalStateException("未注册的规则链: " + chainId));
            return;
        }
        if (!chain.hasNode(nodeId)) {
            callback.onError(new IllegalStateException(
                    "规则链 " + chainId + " 中不存在节点: " + nodeId));
            return;
        }
        // 复用 DefaultNodeContext 的路由逻辑：它持有 nodeId，查邻接表即可
        INodeContext ctx = new DefaultINodeContext(this, chain, nodeId,
                expressionEngine, asyncExecutor, sink, capabilities, callback);
        ctx.tellNext(msg, relations);
    }

    /** 把消息交给指定节点处理。 */
    public void dispatch(final RuleChain chain, final String nodeId,
                         final RuleMessage msg, final ExecutionCallback callback) {
        RuleNode node = chain.node(nodeId);
        if (node == null) {
            callback.onComplete();
            return;
        }
        INodeContext ctx = new DefaultINodeContext(this, chain, nodeId,
                expressionEngine, asyncExecutor, sink, capabilities, callback);

        long start = System.nanoTime();
        try {
            node.onMsg(ctx, msg.transform().chainId(chain.getId()).nodeId(nodeId).build());
        } catch (Exception e) {
            observer.onNodeError(chain.getId(), nodeId, e);
            callback.onError(e);
            return;
        }
        observer.onNodeFinished(chain.getId(), nodeId, System.nanoTime() - start);
    }

    /** tellSelf：延迟投递给自己，用于周期任务 / 超时检查。 */
    public void scheduleSelf(final RuleChain chain, final String nodeId,
                             final RuleMessage msg, long delayMs) {
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                dispatch(chain, nodeId, msg, ExecutionCallback.NOOP);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        scheduler.shutdown();
        for (RuleChain chain : chains.values()) {
            chain.destroy();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private IExpressionEngine expressionEngine;
        private Executor asyncExecutor;
        private IMessageSink sink;
        private EngineObserver observer;
        private Map<Class<?>, Object> capabilities;
        private String defaultChainId;

        /** 消息未携带 chainId 时使用的默认链。 */
        public Builder defaultChainId(String defaultChainId) {
            this.defaultChainId = defaultChainId;
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

        /** 注册业务能力，节点通过 ctx.capability(Xxx.class) 获取。 */
        public <T> Builder capability(Class<T> type, T impl) {
            if (capabilities == null) {
                capabilities = new HashMap<Class<?>, Object>();
            }
            capabilities.put(type, impl);
            return this;
        }

        /** 批量注册业务能力。 */
        public Builder capabilities(Map<Class<?>, Object> map) {
            if (map == null) {
                return this;
            }
            if (capabilities == null) {
                capabilities = new HashMap<Class<?>, Object>();
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
