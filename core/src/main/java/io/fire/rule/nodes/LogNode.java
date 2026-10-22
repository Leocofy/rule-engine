package io.fire.rule.nodes;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.NodeConfig;
import io.fire.rule.spi.NodeException;
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

    private String prefix;

    @Override
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {
        this.prefix = config.getOrDefault("prefix", "[rule]");
    }

    @Override
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
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

    @Override
    public String[] relations() {
        return new String[0];
    }

    @Override
    public boolean customRelations() {
        return false;
    }

    @Override
    public String version() {
        return "1.0";
    }
}
