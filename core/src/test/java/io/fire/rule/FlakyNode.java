package io.fire.rule;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.*;

/** 前 N 次调用抛异常 */
@NodeComponent(type = "flaky", category = "debug", relations = {"Success", "Failure"})
public class FlakyNode implements RuleNode {
    public interface FailSwitch { boolean shouldFail(); }
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {}
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        FailSwitch sw = ctx.capability(FailSwitch.class);
        if (sw != null && sw.shouldFail()) {
            throw new RuntimeException("模拟失败");
        }
        ctx.ack(msg);
    }
}
