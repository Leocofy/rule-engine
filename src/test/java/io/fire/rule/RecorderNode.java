package io.fire.rule;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.*;

@NodeDefinition(type = "recorder", category = "debug")
public class RecorderNode implements RuleNode {
    public interface Sink { void accept(String s); }
    private String tag;
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {
        this.tag = config.getOrDefault("tag", "");
    }
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        Sink sink = ctx.capability(Sink.class);
        String p = msg.getPayload() == null ? "" : msg.getPayload();
        String seq = p.contains("seq=") ? p.substring(p.indexOf("seq=") + 4) : p;
        if (sink != null) sink.accept(tag + seq);
        ctx.tellSuccess(msg);
    }
}
