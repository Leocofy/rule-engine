package io.fire.rule.chain;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.IExpressionEngine;
import io.fire.rule.spi.IMessageSink;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.NodeConfig;

import java.util.*;
import java.util.concurrent.Executor;

/**
 * 节点上下文默认实现：路由语义的真实落点。
 *
 * <p><b>tellNext 与 enqueueForTellNext 的区别（关键）</b>：
 * <ul>
 *   <li>tellNext —— 内存投递，不序列化，同 JVM 内流转</li>
 *   <li>enqueueForTellNext —— 落队列，关系名随消息一起传递，可跨实例、可恢复</li>
 * </ul>
 * 单机跑得顺、一上集群就出问题，通常就是漏了后者。
 */
public class DefaultNodeContext implements INodeContext {

    private static final Set<String> SUCCESS_ONLY =
            Collections.unmodifiableSet(new LinkedHashSet<>(Collections.singleton(REL_SUCCESS)));

    private final ChainExecutor executor;
    private final RuleChain chain;
    private final String nodeId;
    private final IExpressionEngine expressionEngine;
    private final Executor asyncExecutor;
    private final IMessageSink sink;
    private final Map<Class<?>, Object> capabilities;
    private final ExecutionCallback callback;
    private final ExecutionContext execCtx;

    private DefaultNodeContext(Builder b) {
        this.executor = b.executor;
        this.chain = b.chain;
        this.expressionEngine = b.expressionEngine;
        this.asyncExecutor = b.asyncExecutor;
        this.sink = b.sink;
        this.capabilities = b.capabilities != null ? b.capabilities : new HashMap<>();
        this.callback = b.callback;
        this.execCtx = b.execCtx;
        this.nodeId = execCtx != null ? execCtx.getNodeId() : b.nodeId;
    }

    @Override
    public void tellSuccess(RuleMessage msg) {
        tellNext(msg, REL_SUCCESS);
    }

    @Override
    public void tellNext(RuleMessage msg, String relationType) {
        tellNext(msg, Collections.singleton(relationType));
    }

    @Override
    public void tellNext(RuleMessage msg, Set<String> relationTypes) {
        if (msg == null || relationTypes == null || relationTypes.isEmpty()) {
            return;
        }
        if (!isValid()) {
            return; // 批次已超时/取消，放弃处理
        }

        // 先统计本次将派生出的分支总数，在开始派发前一次性声明。
        // 顺序很关键：必须在任何 dispatch 之前，否则先完成的分支会让批次提前收口。
        int totalTargets = 0;
        for (String relation : relationTypes) {
            totalTargets += chain.downstream(nodeId, relation).size();
        }
        if (totalTargets > 1) {
            callback.onFanOut(totalTargets);
        }

        boolean anyRouted = false;
        for (String relation : relationTypes) {
            List<String> targets = chain.downstream(nodeId, relation);
            if (targets.isEmpty()) {
                continue;
            }
            for (String target : targets) {
                anyRouted = true;
                executor.dispatch(chain, target, msg, execCtx, callback);
            }
        }

        // 没有任何下游接住这条关系 → 走到链路尽头。
        // 若当前处于嵌套链中，必须回跳到调用者链，否则子链结果会被静默吞掉。
        if (!anyRouted) {
            output(msg, REL_SUCCESS);
        }
    }

    @Override
    public void tellFailure(RuleMessage msg, Throwable t) {
        List<String> targets = chain.downstream(nodeId, REL_FAILURE);
        if (targets.isEmpty()) {
            // Failure 无人接管 → 整条消息判失败
            if (callback != null) {
                callback.onError(t != null ? t : new RuntimeException("node failed: " + nodeId));
            }
            return;
        }
        for (String target : targets) {
            executor.dispatch(chain, target, msg, execCtx, callback);
        }
    }

    @Override
    public void tellSelf(RuleMessage msg, long delayMs) {
        executor.scheduleSelf(chain, nodeId, msg, execCtx, delayMs);
    }

    @Override
    public void enqueueForTellNext(RuleMessage msg, String relationType) {
        enqueueForTellNext(msg, Collections.singleton(relationType));
    }

    /**
     * 跨节点投递：关系名随消息一起序列化，消费端据此继续路由。
     */
    public void enqueueForTellNext(RuleMessage msg, Set<String> relationTypes) {
        if (!isValid()) {
            return;
        }
        if (sink == null) {
            // 未配置跨节点通道时退化为本地投递，保证单机可用
            tellNext(msg, relationTypes);
            return;
        }
        sink.send(chain.getId(), nodeId, msg, relationTypes);
    }

    @Override
    public void input(RuleMessage msg, String chainId) {
        if (!isValid()) {
            return;
        }
        // 循环检测：同一 (chain, node) 已在栈中说明成环
        if (execCtx.alreadyInStack(chainId, nodeId)) {
            tellFailure(msg, new IllegalStateException(
                    "检测到规则链循环引用: chain=" + chainId + ", node=" + nodeId));
            return;
        }
        ExecutionContext childCtx = execCtx.copy();
        childCtx.setChainId(chainId);
        childCtx.setNodeId(null);
        childCtx.pushToStack(chain.getId(), nodeId);   // 压栈：记住从哪来
        executor.execute(msg, childCtx, callback);
    }

    @Override
    public void output(RuleMessage msg, String relationType) {
        outputRecursive(msg, relationType == null ? REL_SUCCESS : relationType);
    }

    /**
     * 回跳实现：出栈拿到调用者，把消息送到<b>调用者节点的下游</b>。
     *
     * <p>注意不能 dispatch 到调用者节点本身——那会重新触发 input，形成死循环。
     * 若调用者节点在该关系下也没有下游，继续向上回溯（支持多层嵌套）。
     */
    private void outputRecursive(RuleMessage msg, String relation) {
        String[] caller = execCtx.popFromStack();
        if (caller == null) {
            complete(msg);                          // 已回到顶层链，确认完成
            return;
        }
        RuleChain callerChain = executor.chain(caller[0]);
        if (callerChain == null) {
            complete(msg);
            return;
        }
        List<String> targets = callerChain.downstream(caller[1], relation);
        if (targets.isEmpty()) {
            outputRecursive(msg, relation);         // 继续向上回溯
            return;
        }
        for (String target : targets) {
            executor.dispatch(callerChain, target, msg, execCtx, callback);
        }
    }

    @Override
    public void ack(RuleMessage msg) {
        complete(msg);
    }

    private void complete(RuleMessage msg) {
        if (callback != null) {
            callback.onComplete();
        }
    }

    @Override
    public Executor asyncExecutor() {
        return asyncExecutor;
    }

    @Override
    public IExpressionEngine expressionEngine() {
        return expressionEngine;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T capability(Class<T> type) {
        Object c = capabilities.get(type);
        return c == null ? null : (T) c;
    }

    @Override
    public NodeConfig nodeConfig() {
        return chain.nodeConfig(nodeId);
    }

    @Override
    public boolean isValid() {
        return callback != null && callback.isValid();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        protected ChainExecutor executor;
        protected RuleChain chain;
        protected String nodeId;
        protected IExpressionEngine expressionEngine;
        protected Executor asyncExecutor;
        protected IMessageSink sink;
        protected Map<Class<?>, Object> capabilities;
        protected ExecutionCallback callback;
        protected ExecutionContext execCtx;

        public Builder executor(ChainExecutor executor) {
            this.executor = executor;
            return this;
        }

        public Builder chain(RuleChain chain) {
            this.chain = chain;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
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

        public Builder capabilities(Class<?> clazz, Object capabilities) {
            this.capabilities.put(clazz, capabilities);
            return this;
        }

        public Builder capabilities(Map<Class<?>, Object> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public Builder callback(ExecutionCallback callback) {
            this.callback = callback;
            return this;
        }

        public Builder execCtx(ExecutionContext execCtx) {
            this.execCtx = execCtx;
            return this;
        }

        public DefaultNodeContext build() {
            return new DefaultNodeContext(this);
        }
    }
}
