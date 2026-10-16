package io.fire.rule.nodes;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.NodeConfig;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.NodeDefinition;
import io.fire.rule.spi.NodeException;
import io.fire.rule.spi.RuleNode;

/**
 * 最简同步节点：打印消息并直接放行。
 *
 * <p>注意 {@code onMsg} 里每个分支都要有出口——
 * 漏掉 tellSuccess 消息会一直挂在批次里，直到批次超时才被清理。
 */
@NodeDefinition(type = "log", category = "debug",
        description = "打印消息内容并放行")
public class LogNode implements RuleNode {

    private String prefix;

    @Override
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {
        this.prefix = config.getOrDefault("prefix", "[rule]");
    }

    @Override
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        System.out.println(prefix + " " + msg.getType()
                + " originator=" + msg.getOriginator()
                + " payload=" + msg.getPayload()
                + " meta=" + msg.getMetaData().values());
        ctx.tellSuccess(msg);
    }
}
