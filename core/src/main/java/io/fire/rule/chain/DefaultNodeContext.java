package io.fire.rule.chain;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            Collections.unmodifiableSet(new LinkedHashSet<>(Collections.singleton(NodeDefinition.Relations.SUCCESS)));

    private final ChainExecutor executor;
    private final RuleChain chain;
    private final String nodeId;
    private final IExpressionEngine expressionEngine;
    private final Executor asyncExecutor;
    private final IMessageSink sink;
    private final Map<Class<?>, Object> capabilities;
    private final ExecutionCallback callback;

    public DefaultNodeContext(ChainExecutor executor, RuleChain chain, String nodeId,
                              IExpressionEngine expressionEngine, Executor asyncExecutor,
                              IMessageSink sink, Map<Class<?>, Object> capabilities,
                              ExecutionCallback callback) {
        this.executor = executor;
        this.chain = chain;
        this.nodeId = nodeId;
        this.expressionEngine = expressionEngine;
        this.asyncExecutor = asyncExecutor;
        this.sink = sink;
        this.capabilities = capabilities != null ? capabilities : new HashMap<>();
        this.callback = callback;
    }

    @Override
    public void tellSuccess(RuleMessage msg) {
        tellNext(msg, NodeDefinition.Relations.SUCCESS);
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
        if (!msg.isValid()) {
            return; // 批次已超时/取消，放弃处理
        }

        // 先统计本次将派生出的分支总数，在开始派发前一次性声明。
        // 顺序很关键：必须在任何 dispatch 之前，否则先完成的分支会让批次提前收口。
        int totalTargets = 0;
        for (String relation : relationTypes) {
            totalTargets += chain.downstream(nodeId, relation).size();
        }
        if (totalTargets > 1) {
            msg.getCallback().onFanOut(totalTargets);
        }

        boolean anyRouted = false;
        for (String relation : relationTypes) {
            List<String> targets = chain.downstream(nodeId, relation);
            if (targets.isEmpty()) {
                continue;
            }
            for (String target : targets) {
                anyRouted = true;
                executor.dispatch(chain, target, msg.transform()
                        .chainId(chain.getId())
                        .nodeId(target)
                        .build(), callback);
            }
        }

        // 没有任何下游接住这条关系 → 走到链路尽头。
        // 若当前处于嵌套链中，必须回跳到调用者链，否则子链结果会被静默吞掉。
        if (!anyRouted) {
            output(msg, NodeDefinition.Relations.SUCCESS);
        }
    }

    @Override
    public void tellFailure(RuleMessage msg, Throwable t) {
        List<String> targets = chain.downstream(nodeId, NodeDefinition.Relations.FAILURE);
        if (targets.isEmpty()) {
            // Failure 无人接管 → 整条消息判失败
            if (callback != null) {
                callback.onError(t != null ? t : new RuntimeException("node failed: " + nodeId));
            }
            return;
        }
        for (String target : targets) {
            executor.dispatch(chain, target, msg.transform()
                    .chainId(chain.getId())
                    .nodeId(target)
                    .build(), callback);
        }
    }

    @Override
    public void tellSelf(RuleMessage msg, long delayMs) {
        executor.scheduleSelf(chain, nodeId, msg, delayMs);
    }

    @Override
    public void enqueueForTellNext(RuleMessage msg, String relationType) {
        enqueueForTellNext(msg, Collections.singleton(relationType));
    }

    /** 跨节点投递：关系名随消息一起序列化，消费端据此继续路由。 */
    public void enqueueForTellNext(RuleMessage msg, Set<String> relationTypes) {
        if (!msg.isValid()) {
            return;
        }
        if (sink == null) {
            // 未配置跨节点通道时退化为本地投递，保证单机可用
            tellNext(msg, relationTypes);
            return;
        }
        sink.send(msg.getChainId(), msg.transform()
                .chainId(chain.getId())
                .nodeId(nodeId)
                .build(), relationTypes);
    }

    @Override
    public void input(RuleMessage msg, String chainId) {
        if (!msg.isValid()) {
            return;
        }
        // 循环检测：同一 (chain, node) 已在栈中说明成环
        if (msg.alreadyInStack(chainId, nodeId)) {
            tellFailure(msg, new IllegalStateException(
                    "检测到规则链循环引用: chain=" + chainId + ", node=" + nodeId));
            return;
        }
        RuleMessage next = msg.copy()
                .chainId(chainId)
                .resetNodeId()
                .build();
        next.pushToStack(chain.getId(), nodeId);   // 压栈：记住从哪来
        executor.execute(next, callback);
    }

    @Override
    public void output(RuleMessage msg, String relationType) {
        outputRecursive(msg, relationType == null ? NodeDefinition.Relations.SUCCESS : relationType);
    }

    /**
     * 回跳实现：出栈拿到调用者，把消息送到<b>调用者节点的下游</b>。
     *
     * <p>注意不能 dispatch 到调用者节点本身——那会重新触发 input，形成死循环。
     * 若调用者节点在该关系下也没有下游，继续向上回溯（支持多层嵌套）。
     */
    private void outputRecursive(RuleMessage msg, String relation) {
        String[] caller = msg.popFromStack();
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
            executor.dispatch(callerChain, target, msg.transform()
                    .chainId(caller[0])
                    .nodeId(target)
                    .build(), callback);
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
    public RuleMessage newMessage(String type, String originator, String payload) {
        return RuleMessage.newMessage()
                .type(type)
                .originator(originator)
                .payload(payload)
                .build();
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
    public String getChainId() {
        return chain.getId();
    }

    @Override
    public String getNodeId() {
        return nodeId;
    }
}
