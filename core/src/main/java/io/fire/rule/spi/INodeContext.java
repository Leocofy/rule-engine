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

    /**
     * 成功关系名：默认路由出口，等价于 {@link #tellNext} 传入该关系。
     */
    String REL_SUCCESS = "Success";

    /**
     * 失败关系名：由 {@link #tellFailure} 走的关系，无下游接管则整条消息判失败。
     */
    String REL_FAILURE = "Failure";

    // ---------- 消息流控 ----------

    /**
     * 以成功关系把消息交给当前节点的下游。
     *
     * <p>等价于 {@code tellNext(msg, REL_SUCCESS)}。
     *
     * @param msg 待投递的消息，为 {@code null} 时直接忽略
     */
    void tellSuccess(RuleMessage msg);

    /**
     * 内存投递：按关系名把消息交给当前节点的下游节点，不序列化、仅同 JVM 内流转。
     *
     * <p>无任何下游接住该关系时视为链路结束，会走 {@link #output} 回跳到调用者链。
     * 需要跨实例 / 可恢复投递时改用 {@link #enqueueForTellNext}。
     *
     * @param msg          待投递的消息
     * @param relationType 关系名，见 {@link #REL_SUCCESS} / {@link #REL_FAILURE} 或节点自定义关系
     */
    void tellNext(RuleMessage msg, String relationType);

    /**
     * 内存投递的批量版本：一次声明多个关系名，交由下游并行分支处理。
     *
     * <p>分支总数会在派发前一次性声明，保证批次计数不会提前收口。
     *
     * @param msg           待投递的消息，为 {@code null} 时直接忽略
     * @param relationTypes 关系名集合，为空时直接忽略
     */
    void tellNext(RuleMessage msg, Set<String> relationTypes);

    /**
     * 以失败关系投递消息，并把异常带入执行上下文。
     *
     * <p>若当前节点没有 Failure 下游，则整条消息判定为失败。
     *
     * @param msg 待投递的消息
     * @param t   失败原因，为 {@code null} 时使用默认异常
     */
    void tellFailure(RuleMessage msg, Throwable t);

    /**
     * 延迟投递给自己：经过 {@code delayMs} 毫秒后重新执行当前节点，常用于重试 / 轮询等待。
     *
     * @param msg     待投递的消息
     * @param delayMs 延迟毫秒数
     */
    void tellSelf(RuleMessage msg, long delayMs);

    /**
     * 跨节点投递：关系名随消息一起交给 {@link IMessageSink} 落队列，可跨实例、可恢复。
     *
     * <p>与 {@link #tellNext} 的区别在于需要序列化关系名，因此集群部署必须用它；
     * 未配置跨节点通道时会退化为本地投递。
     *
     * @param msg          待投递的消息
     * @param relationType 关系名，随消息一同传递
     */
    void enqueueForTellNext(RuleMessage msg, String relationType);

    /**
     * 调用另一条规则链（嵌套链）：新建子上下文并把当前 (chain, node) 压栈，以便子链结束后回跳。
     *
     * <p>压栈前会做循环引用检测，出现环路直接走 {@link #tellFailure}。
     *
     * @param msg     输入给目标链的消息
     * @param chainId 目标链 ID
     */
    void input(RuleMessage msg, String chainId);

    /**
     * 从当前链出口返回：出栈找到调用者，把消息送到调用者节点的下游。
     *
     * <p>已处于顶层链时表示链路完成；调用者在该关系下也无下游则继续向上回溯。
     *
     * @param msg          待返回的消息
     * @param relationType 关系名，为 {@code null} 时按 {@link #REL_SUCCESS} 处理
     */
    void output(RuleMessage msg, String relationType);

    /**
     * 确认消息处理完毕，通知执行回调当前批次完成。
     *
     * @param msg 已处理完的消息
     */
    void ack(RuleMessage msg);

    // ---------- 资源 ----------

    /**
     * 异步执行器：节点做耗时 IO 时用它提交任务，避免阻塞引擎线程。
     */
    Executor asyncExecutor();

    /**
     * 表达式引擎：条件判定 / 负载转换 / 动态路由的统一入口。
     */
    IExpressionEngine expressionEngine();

    /**
     * 获取宿主注入的扩展能力（如 HTTP 客户端、指标上报等）。
     *
     * @param type 能力类型
     * @param <T>  能力类型
     * @return 已注册的实例；未注册返回 {@code null}
     */
    <T> T capability(Class<T> type);

    // ---------- 配置与状态 ----------

    /**
     * 当前节点的配置项，对应链路定义中的 {@code config} 段。
     */
    NodeConfig nodeConfig();

    /**
     * 当前批次是否仍然有效（未超时、未取消）。
     *
     * <p>投递前应检查：大批量派发中途失效时，后续子任务直接放弃处理。
     */
    boolean isValid();

    String getNodeId();

}
