package io.fire.rule.example.node;

import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.example.config.NodeCategory;
import io.fire.rule.spring.annotation.NodeComponent;
import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.core.spi.RuleNode;

/**
 * 告警通知节点：把高风险结论异步推送到外部渠道。
 *
 * <p>与 {@link AsyncSinkNode} 复用同一个 {@link AsyncSinkNode.SinkHandler} capability。
 * 通知成功后按 Success 关系继续向下游放行；没有下游时 next 等价于 ack。
 *
 * <p>注意：引擎按 type 复用节点实例，同一链路中若需要多个不同配置的 sink，
 * 应拆成不同的节点类型（而不是依赖 props 区分），这里正是这么做的。
 *
 * @author Cai FeiYue
 * @date 2026-10-22
 */
@NodeComponent(type = "notify", category = NodeCategory.BUSINESS,
        description = "异步推送告警通知（钉钉 / 短信 / 声光）")
public class NotifyNode implements RuleNode {

    private static final String TARGET = "notify";

    @Override
    public void onMsg(final INodeContext ctx) throws Exception {
        final AsyncSinkNode.SinkHandler handler = ctx.capability(AsyncSinkNode.SinkHandler.class);
        if (handler == null) {
            ctx.fail(new IllegalStateException("未注入 SinkHandler"));
            return;
        }
        final ExecutionState state = ctx.state();
        ctx.asyncExecutor().execute(() -> {
            try {
                if (!ctx.isValid()) {
                    return;
                }
                handler.handle(TARGET, state);
                ctx.next();
            } catch (Exception e) {
                ctx.fail(e);
            }
        });
    }
}
