package io.fire.rule.core.spi;

import io.fire.rule.core.model.ExecutionState;

import java.util.Set;

/**
 * 表达式 / 脚本引擎 SPI。
 *
 * <p>刻意做成接口而非内置实现：
 * <ul>
 *   <li>你已在用 <b>Jsonata</b> 做字段映射，直接实现本接口接入即可</li>
 *   <li>需要更高性能时换 Aviator（编译成字节码）</li>
 *   <li>测试环境可用内置的简单实现，不需要引入重量脚本引擎</li>
 * </ul>
 *
 * <p>统一 State 模型下，求值入参由 {@code RuleMessage} 改为 {@link ExecutionState}，
 * 实现方以 {@code entry / outputs / meta} 为变量根。
 */
public interface IExpressionEngine {

    /** 条件判定，用于 Filter 节点。 */
    boolean evaluate(String expr, ExecutionState state);

    /** 负载转换，返回新的负载 JSON 字符串。 */
    String transform(String expr, ExecutionState state);

    /** 动态路由，返回关系名集合，用于 Switch / AI 判定节点。 */
    Set<String> route(String expr, ExecutionState state);

    /**
     * 表达式求值并返回任意值，供取值解析器处理 {@code expression} 型取值描述符取字段值。
     *
     * <p>默认不支持（返回 {@code null}）；集成层（如 Aviator）可覆盖以提供取值能力。
     *
     * @param expr  表达式
     * @param state 当前执行状态
     * @return 求值结果；不支持时为 {@code null}
     */
    default Object value(String expr, ExecutionState state) {
        return null;
    }
}
