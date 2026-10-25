package io.fire.rule.spi;

import io.fire.rule.model.RuleMessage;

import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 节点运行时上下文：定义了节点"能干什么"。
 *
 * <p>职责分三类：
 * <ul>
 *   <li>消息流控 —— tellSuccess / tellNext / tellFailure / ack / tellSelf / enqueueForTellNext / input / output</li>
 *   <li>资源获取 —— expressionEngine / asyncExecutor / capability</li>
 *   <li>配置与状态 —— nodeConfig / isValid</li>
 * </ul>
 *
 * <p>引擎内部状态（chainId / nodeId / stack / callback）由 ExecutionContext 承载，
 * 不通过此接口暴露。
 */
public interface INodeContext {

    String REL_SUCCESS = "Success";
    String REL_FAILURE = "Failure";

    // ---------- 消息流控 ----------

    void tellSuccess(RuleMessage msg);

    void tellNext(RuleMessage msg, String relationType);

    void tellNext(RuleMessage msg, Set<String> relationTypes);

    void tellFailure(RuleMessage msg, Throwable t);

    void tellSelf(RuleMessage msg, long delayMs);

    void enqueueForTellNext(RuleMessage msg, String relationType);

    void input(RuleMessage msg, String chainId);

    void output(RuleMessage msg, String relationType);

    void ack(RuleMessage msg);

    // ---------- 资源 ----------

    Executor asyncExecutor();

    IExpressionEngine expressionEngine();

    <T> T capability(Class<T> type);

    // ---------- 配置与状态 ----------

    NodeConfig nodeConfig();

    boolean isValid();
}
