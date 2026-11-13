package io.fire.rule.example.node;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.core.spi.RuleNode;
import io.fire.rule.example.config.NodeCategory;
import io.fire.rule.spring.annotation.NodeComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 最简同步节点：打印消息并直接放行。
 *
 * <p>注意 {@code onMsg} 里每个分支都要有出口——
 * 漏掉 next 消息会一直挂在批次里，直到批次超时才被清理。
 */
@NodeComponent(type = "log", name = "日志节点", category = NodeCategory.SYSTEM, order = 1, description = "日志节点")
public class LogNode implements RuleNode {

    private static final Logger LOG = LoggerFactory.getLogger(LogNode.class);

    @Override
    public void onMsg(INodeContext ctx) throws Exception {
        Config config = ctx.config(Config.class);
        String prefix = config.prefix == null ? "[rule]" : config.prefix;
        String level = (config.level == null ? Level.INFO : config.level).name();
        String line = String.format("%s %s node=%s entry=%s meta=%s", prefix, level,
                ctx.getNodeId(), ctx.state().entry(), ctx.state().meta().values());
        switch (level) {
            case "DEBUG":
                LOG.debug(line);
                break;
            case "WARN":
                LOG.warn(line);
                break;
            case "ERROR":
                LOG.error(line);
                break;
            default:
                LOG.info(line);
        }
        ctx.next();
    }

    /**
     * 日志节点配置：字段名与运行期 {@code ctx.nodeConfig()} 读取的键一一对应。
     */
    public static class Config {

        @JsonPropertyDescription("打印在每行开头，便于在混合日志里筛选")
        public String prefix = "[rule]";

        @JsonPropertyDescription("按所选级别输出")
        public Level level = Level.INFO;
    }

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

}
