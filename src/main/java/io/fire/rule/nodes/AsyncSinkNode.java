package io.fire.rule.nodes;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.NodeConfig;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.NodeDefinition;
import io.fire.rule.spi.NodeException;
import io.fire.rule.spi.RuleNode;

/**
 * 异步外部调用节点：示范"耗时 I/O 必须外置线程池"。
 *
 * <p>落库、HTTP、发短信这类操作如果跑在调度线程上，
 * 慢 I/O 会迅速耗尽线程池，导致整个引擎停滞。
 * 官方实现专门提供 dbCallback / externalCall / mail 等独立线程池，就是这个原因。
 */
@NodeDefinition(type = "asyncSink", category = "external",
        description = "异步调用外部系统（落库/HTTP/通知）")
public class AsyncSinkNode implements RuleNode {

    /** 业务实现该接口，引擎不关心具体写什么。 */
    public interface SinkHandler {
        void handle(RuleMessage msg) throws Exception;
    }

    private String target;

    @Override
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {
        this.target = config.getOrDefault("target", "default");
    }

    @Override
    public void onMsg(final INodeContext ctx, final RuleMessage msg) throws Exception {
        final SinkHandler handler = ctx.capability(SinkHandler.class);
        if (handler == null) {
            ctx.tellFailure(msg, new IllegalStateException("未注入 SinkHandler"));
            return;
        }
        ctx.asyncExecutor().execute(() -> {
            try {
                handler.handle(msg);
                ctx.ack(msg);
            } catch (Exception e) {
                ctx.tellFailure(msg, e);
            }
        });
    }
}
