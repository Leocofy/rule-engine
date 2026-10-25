package io.fire.rule.spi;

import io.fire.rule.model.RuleMessage;

/**
 * 规则节点 SPI：无状态、支持单例复用。
 */
@FunctionalInterface
public interface RuleNode {

    void onMsg(INodeContext ctx, RuleMessage msg) throws Exception;
}
