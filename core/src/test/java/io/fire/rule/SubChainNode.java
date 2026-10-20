package io.fire.rule;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.*;

/** 跳到子链，子链 output 后回到本节点的下游 */
@NodeComponent(type = "sub", category = "flow")
public class SubChainNode implements RuleNode {
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {}
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        ctx.input(msg, "sub");
    }
}
