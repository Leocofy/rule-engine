package io.fire.rule;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.*;
/** 输出 hot 关系并触发跨节点投递 */
@NodeComponent(type = "cross", category = "debug", relations = {"hot", "cold"}, customRelations = true)
public class CrossNode implements RuleNode {
    public interface Counter { void inc(); }
    public void init(INodeContext c, NodeConfig cfg) {}
    public void onMsg(INodeContext ctx, RuleMessage msg) {
        Counter cnt = ctx.capability(Counter.class);
        if (cnt != null) cnt.inc();
        ctx.enqueueForTellNext(msg, "hot");
    }
}
