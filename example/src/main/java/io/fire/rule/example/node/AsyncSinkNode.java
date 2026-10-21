package io.fire.rule.example.node;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.NodeComponent;
import io.fire.rule.spi.NodeConfig;
import io.fire.rule.spi.NodeException;
import io.fire.rule.spi.RuleNode;

/**
 * 异步外部调用节点：示范"耗时 I/O 必须外置线程池"。
 *
 * <p>业务侧实现 {@link SinkHandler} 并通过 {@code RuleEngineCustomizer}
 * 注入 capability。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@NodeComponent(type = "asyncSink", category = "external",
        description = "异步调用外部系统（落库/HTTP/通知）")
public class AsyncSinkNode implements RuleNode {

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
