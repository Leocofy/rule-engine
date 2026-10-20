package io.fire.rule;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.*;
@NodeComponent(type = "trace", category = "debug")
public class TraceNode implements RuleNode {
    public interface Sink { void accept(String s); }
    private String tag;
    public void init(INodeContext c, NodeConfig cfg) { tag = cfg.getOrDefault("tag", "T"); }
    public void onMsg(INodeContext ctx, RuleMessage msg) {
        Sink s = ctx.capability(Sink.class);
        if (s != null) s.accept(tag + ":" + msg.getPayload());
        ctx.tellSuccess(msg);
    }
}
