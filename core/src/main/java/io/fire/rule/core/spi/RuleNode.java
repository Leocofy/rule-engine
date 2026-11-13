package io.fire.rule.core.spi;

/**
 * 规则节点 SPI：无状态、支持单例复用。
 *
 * <p>统一 State 模型下入口收敛为单参：节点的输入状态、自身配置、投递能力都由 {@link INodeContext} 承载，
 * 处理结果通过 {@code ctx.emit(out)} 暂存、{@code ctx.next(port)} 投递，不再显式传递消息。
 */
@FunctionalInterface
public interface RuleNode {

    void onMsg(INodeContext ctx) throws Exception;
}
