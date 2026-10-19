package io.fire.rule.nodes;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 最简同步节点：打印消息并直接放行。
 *
 * <p>注意 {@code onMsg} 里每个分支都要有出口——
 * 漏掉 tellSuccess 消息会一直挂在批次里，直到批次超时才被清理。
 */
@NodeDefinition(type = "log", category = "debug",
        description = "打印消息内容并放行")
public class LogNode implements RuleNode {

    private static final Logger LOG = LoggerFactory.getLogger(LogNode.class);

    private String prefix;

    @Override
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {
        this.prefix = config.getOrDefault("prefix", "[rule]");
    }

    @Override
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        LOG.info("{} {} \noriginator={} \npayload={} \nmeta={}", prefix, msg.getType(),
                msg.getOriginator(), msg.getPayload(), msg.getMetaData().values());
        ctx.tellSuccess(msg);
    }
}
