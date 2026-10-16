package io.fire.rule;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.*;

@NodeDefinition(type = "stress", category = "debug")
public class StressNode implements RuleNode {
    public interface Sink { void accept(String dev, int seq); }
    public void init(INodeContext ctx, NodeConfig c) throws NodeException {}
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        Sink s = ctx.capability(Sink.class);
        String p = msg.getPayload();
        int seq = Integer.parseInt(p.substring(p.indexOf("=") + 1));
        if (s != null) s.accept(msg.getOriginator(), seq);
        ctx.tellSuccess(msg);
    }
}
