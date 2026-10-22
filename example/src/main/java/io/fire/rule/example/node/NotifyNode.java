package io.fire.rule.example.node;

import io.fire.rule.config.NodeComponent;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.NodeConfig;
import io.fire.rule.spi.NodeException;
import io.fire.rule.spi.RuleNode;

/**
 * 告警通知节点：把高风险结论异步推送到外部渠道。
 *
 * <p>与 {@link AsyncSinkNode} 复用同一个 {@link AsyncSinkNode.SinkHandler} capability。
 * 通知成功后按 Success 关系继续向下游放行；没有下游时 tellSuccess 等价于 ack。
 *
 * <p>注意：引擎按 type 复用节点实例，同一链路中若需要多个不同配置的 sink，
 * 应拆成不同的节点类型（而不是依赖 props 区分），这里正是这么做的。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@NodeComponent(type = "notify", category = "external",
        description = "异步推送告警通知（钉钉 / 短信 / 声光）")
public class NotifyNode implements RuleNode {

    private static final String TARGET = "notify";

    @Override
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {
        // 通知渠道由 alert.channels 决定，节点无需配置
    }

    @Override
    public void onMsg(final INodeContext ctx, final RuleMessage msg) throws Exception {
        final AsyncSinkNode.SinkHandler handler = ctx.capability(AsyncSinkNode.SinkHandler.class);
        if (handler == null) {
            ctx.tellFailure(msg, new IllegalStateException("未注入 SinkHandler"));
            return;
        }
        ctx.asyncExecutor().execute(() -> {
            try {
                if (!msg.isValid()) {
                    return;
                }
                handler.handle(TARGET, msg);
                ctx.tellSuccess(msg);
            } catch (Exception e) {
                ctx.tellFailure(msg, e);
            }
        });
    }
}
