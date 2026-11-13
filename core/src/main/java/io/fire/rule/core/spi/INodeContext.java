package io.fire.rule.core.spi;

import io.fire.rule.core.chain.InputsWriter;
import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.model.NodeConfig;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 节点运行时上下文：定义了节点"能干什么"。
 *
 * <p>统一 State 模型（方案 A · D2）下的职责分三类：
 * <ul>
 *   <li>消息流控 —— {@code emit}/{@code next}/{@code fail}/{@code retrySelf}/{@code enqueueNext}/{@code input}/{@code output}/{@code ack}：
 *       节点只表达"本节点产出什么、从哪个端口走"，不再搬运消息</li>
 *   <li>资源与取值 —— expressionEngine / asyncExecutor / capability / state / resolvedInputs / variable</li>
 *   <li>配置与状态 —— nodeConfig / bind / isValid</li>
 * </ul>
 *
 * <p><b>端口（port）</b>是边的标签：串联场景走 {@link #PORT_DEFAULT}。
 * 引擎内部状态（chainId / nodeId / stack / callback）由 ExecutionContext 承载，不通过此接口暴露。
 */
public interface INodeContext {

    /**
     * 默认输出端口：串联边与 {@link #next(String)} 走这里。
     */
    String PORT_DEFAULT = "default";

    /**
     * 异常输出端口：{@link #fail} 走这里，无下游接管则整条消息判失败。
     */
    String PORT_EXCEPTION = "exception";

    // ---------- 消息流控 ----------

    /**
     * 暂存本节点产出，作为后续 {@link #next}/{@link #ack} 时写入命名空间 {@code outputs[nodeId]} 的内容。
     * 多次调用以最后一次为准；不调用表示透传（不产出）。
     */
    void emit(Object output);

    /**
     * 以默认端口把本节点产出交给下游，等价于 {@code next(PORT_DEFAULT)}。
     */
    default void next() {
        next(PORT_DEFAULT);
    }

    /**
     * 内存投递：按端口把（已 {@link #emit} 暂存的）产出交给当前节点的下游，不序列化、仅同 JVM 内流转。
     *
     * <p>无任何下游接住该端口时视为链路结束，会走 {@link #output} 回跳到调用者链。
     *
     * @param port 输出端口 ID，见 {@link #PORT_DEFAULT} / {@link #PORT_EXCEPTION} 或节点自定义端口
     */
    default void next(String port) {
        next(Collections.singleton(port));
    }

    /**
     * 暂存产出并按单端口投递的便捷写法，等价于 {@code emit(output); next(port);}。
     */
    default void next(Object output, String port) {
        emit(output);
        next(port);
    }

    /**
     * 内存投递的批量版本：一次声明多个端口，交由下游分支处理。
     * 分支总数会在派发前一次性声明，保证批次计数不会提前收口。
     *
     * @param ports 端口集合，为空时直接忽略
     */
    void next(Set<String> ports);

    /**
     * 以<b>显式状态</b>为基态按端口投递下游（通用编排原语，不含任何循环语义）。
     *
     * <p>与 {@link #next(Set)} 的区别：基态不是本节点进入时的 {@link #state()}，而是调用方计算好的
     * {@code carry}（例如聚合了多轮子链产出的累积快照）；本节点 {@link #emit} 暂存的产出仍会写入
     * {@code outputs[nodeId]} 后一并下传。
     */
    void next(ExecutionState carry, Set<String> ports);

    /**
     * {@link #next(ExecutionState, Set)} 的单端口便捷写法。
     */
    default void next(ExecutionState carry, String port) {
        next(carry, Collections.singleton(port));
    }

    /**
     * 以异常端口投递，并把异常带入执行上下文；若没有异常端口下游，则整条消息判定失败。
     *
     * @param t 失败原因，为 {@code null} 时使用默认异常
     */
    void fail(Throwable t);

    /**
     * 延迟投递给自己：经过 {@code delayMs} 毫秒后重新执行当前节点，常用于重试 / 轮询等待。
     */
    void retrySelf(long delayMs);

    /**
     * 跨节点投递：端口随状态一起交给 {@link IMessageSink} 落队列，可跨实例、可恢复。
     * 未配置跨节点通道时会退化为本地投递。
     *
     * @param port 输出端口，随状态一同传递
     */
    void enqueueNext(String port);

    /**
     * 调用另一条规则链（嵌套链 / 子链）：新建子上下文并把当前 (chain, node) 压栈，以便子链结束后回跳。
     * 压栈前会做循环引用检测，出现环路直接走 {@link #fail}。
     *
     * @param chainId 目标链 ID
     */
    void input(String chainId);

    /**
     * 从当前链出口返回：出栈找到调用者，把状态送到调用者节点的下游端口。
     *
     * @param port 端口，为 {@code null} 时按 {@link #PORT_DEFAULT} 处理
     */
    void output(String port);

    /**
     * 确认消息处理完毕：写入本节点产出后通知执行回调当前批次完成。
     */
    void ack();

    // ---------- 逃生舱（D1：改写型节点直接操作状态） ----------

    /**
     * 整体替换入口负载（working data）。
     * 供 {@code httpPull}（用响应替换 payload）/ {@code riskEnrich}（在原负载上追加字段后回写）等改写型节点使用。
     * 多次调用以最后一次为准；不调用表示入口负载保持不变。
     */
    void replaceEntry(Map<String, Object> entry);

    /**
     * 向元数据累积写入一个键值（不影响入口负载与命名空间产出）。
     * 供校验节点记录 {@code invalidReason}、httpPull "保留原负载"时把响应写入指定键等使用。
     */
    void metaPut(String key, Object value);

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

    /**
     * 获取<b>必需</b>能力：未注册时直接抛 fatal 的 {@link NodeException}。
     *
     * <p>用于避免节点内部因 {@code null} 抛 NPE，被运行层误判为可重试异常。
     */
    default <T> T requireCapability(Class<T> type) {
        T cap = capability(type);
        if (cap == null) {
            throw new NodeException("节点缺少必需能力: " + type.getName(), true);
        }
        return cap;
    }

    // ---------- 取值与作用域 ----------

    /**
     * 当前节点进入时的执行状态快照（入口负载 + 上游命名空间产出 + 元数据 + 作用域）。
     */
    ExecutionState state();

    /**
     * 当前节点执行前已解析的输入：委托注入的取值解析器把节点声明的输入取值描述坍缩成真实值。
     * 无声明式输入时返回空 Map（具体 DSL 由集成层的 {@code IValueResolver} 决定）。
     */
    Object inputs();

    /**
     * 获取元数据：节点运行时可附加的键值对。
     */
    <T> T getMetaData(String key, Class<T> type);

    /**
     * 同步运行一条已注册子链，以 {@code seed} 作为入口状态，返回运行结束后的<b>累积状态快照</b>。
     *
     * <p>通用编排原语（不含循环概念）：供需要"驱动一段子图并拿回终态"的节点使用。子链内节点照常
     * 经 {@code emit/next} 写命名空间，运行到尽头即收口；本方法在独立子上下文中顺序跑完所有入口。
     *
     * @param chainId 已注册子链 ID
     * @param seed    入口状态快照
     * @return 子链跑完后的最终状态；子链不存在或无入口时原样返回 {@code seed}
     */
    ExecutionState runSubChain(String chainId, ExecutionState seed);

    // ---------- 配置与状态 ----------

    /**
     * 解析表达式叶子：把 {@code value} 中的 {@code ${...}} 占位符坍缩成真实值。
     */
    Object resolveValue(Object value, IExpressionEngine expressionEngine);

    /**
     * 解析表达式叶子：把 {@code value} 中的 {@code ${...}} 占位符坍缩成真实值。
     */
    default Object resolveValue(Object value) {
        return resolveValue(value, null);
    }

    void withInput(String key, Object value);

    default Object withInput(String key, Supplier<Object> resolver) {
        Object value = resolver.get();
        withInput(key, value);
        return value;
    }

    /**
     * 当前节点的配置项：节点 {@code data} 段的只读视图（含各集成层自定义的输入/参数键）。
     */
    NodeConfig nodeConfig();

    /**
     * 把节点配置绑定为强类型对象，并解析其中的 IFlowValue 叶子。
     * 取值，适用于结构式配置节点。core 默认返回 {@code null}，由集成层实现。
     */
    default <T> T config(Class<T> type) {
        throw new NodeException("unsupported node config: " + type.getName());
    }

    /**
     * 当前批次是否仍然有效（未超时、未取消）。投递前应检查。
     */
    boolean isValid();

    String getNodeId();
}
