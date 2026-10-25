package io.fire.rule.nodes;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.RuleNodeDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 最简同步节点：打印消息并直接放行。
 *
 * <p>注意 {@code onMsg} 里每个分支都要有出口——
 * 漏掉 tellSuccess 消息会一直挂在批次里，直到批次超时才被清理。
 */
public class LogNode implements RuleNodeDefinition {

    private static final Logger LOG = LoggerFactory.getLogger(LogNode.class);

    @Override
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        String prefix = ctx.nodeConfig().getString("prefix", "[rule]");
        LOG.info("{} {} originator={} payload={} meta={}", prefix, msg.getType(),
                msg.getOriginator(), msg.getPayload(), msg.getMetaData().values());
        ctx.tellSuccess(msg);
    }

    @Override
    public String type() {
        return "log";
    }

    @Override
    public String title() {
        return "日志打印";
    }

    @Override
    public String category() {
        return "debug";
    }

    @Override
    public String description() {
        return "打印消息内容并放行";
    }
}
