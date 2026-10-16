package io.fire.rule.spi;

import io.fire.rule.model.RuleMessage;

import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 节点运行时上下文：定义了节点"能干什么"。
 *
 * <p>这是整个引擎最核心的接口，路由语义全在这里：
 * <ul>
 *   <li>{@link #tellNext} —— 按关系名投递，<b>编排逻辑不写 if/else</b></li>
 *   <li>{@link #enqueueForTellNext} —— 跨节点/跨实例投递，走队列</li>
 *   <li>{@link #ack} —— 本节点处理完成</li>
 * </ul>
 *
 * <p>刻意不提供"上帝接口"（不暴露几十个业务 Service），
 * 业务能力通过 {@link #capability(Class)} 按需获取，避免节点与平台死耦合。
 */
public interface INodeContext {

    // ---------- 消息流控 ----------

    /** 投递到所有通过 "Success" 关系连接的下游节点。 */
    void tellSuccess(RuleMessage msg);

    /** 按单个关系名投递。 */
    void tellNext(RuleMessage msg, String relationType);

    /** 按多个关系名投递（fan-out）。 */
    void tellNext(RuleMessage msg, Set<String> relationTypes);

    /** 走 Failure 关系。 */
    void tellFailure(RuleMessage msg, Throwable t);

    /** 延迟投给自己，用于周期任务 / 超时检查（不落队列，重启丢失）。 */
    void tellSelf(RuleMessage msg, long delayMs);

    /** 跨节点投递：序列化后进队列，保证崩溃可恢复。 */
    void enqueueForTellNext(RuleMessage msg, String relationType);

    /** 进入嵌套规则链（会做循环检测）。 */
    void input(RuleMessage msg, String chainId);

    /** 从嵌套链回到调用者链；已处于顶层则直接 ack。 */
    void output(RuleMessage msg, String relationType);

    /** 本节点处理完成，确认。 */
    void ack(RuleMessage msg);

    // ---------- 消息构造 ----------

    RuleMessage newMessage(String type, String originator, String payload);

    // ---------- 资源 ----------

    /** 耗时 I/O 专用线程池，避免占满调度线程。 */
    Executor asyncExecutor();

    /** 表达式/脚本引擎，可插拔（Jsonata、Aviator、Groovy 等）。 */
    IExpressionEngine expressionEngine();

    /** 按需获取业务能力，避免接口膨胀成上帝对象。 */
    <T> T capability(Class<T> type);

    String getChainId();

    String getNodeId();
}
