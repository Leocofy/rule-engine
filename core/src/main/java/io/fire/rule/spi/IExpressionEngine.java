package io.fire.rule.spi;

import io.fire.rule.model.RuleMessage;

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
 */
public interface IExpressionEngine {

    /** 条件判定，用于 Filter 节点。 */
    boolean evaluate(String expr, RuleMessage msg);

    /** 负载转换，返回新的 payload 字符串。 */
    String transform(String expr, RuleMessage msg);

    /** 动态路由，返回关系名集合，用于 Switch / AI 判定节点。 */
    Set<String> route(String expr, RuleMessage msg);
}
