package io.fire.rule.example.node;

import io.fire.rule.config.NodeComponent;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.RuleNode;

/**
 * 归档节点：低风险样本与无效报文统一落到"样本库"，供后续模型复盘。
 *
 * <p>归档是轻量写操作，这里同步调用 handler；
 * 真实项目若归档到对象存储等耗时系统，应参照 {@link NotifyNode} 提交到 asyncExecutor。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@NodeComponent(type = "archive", category = "storage",
        description = "归档低风险 / 无效样本，供模型复盘与追溯")
public class ArchiveNode implements RuleNode {

    private static final String TARGET = "archive";

    @Override
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        AsyncSinkNode.SinkHandler handler = ctx.capability(AsyncSinkNode.SinkHandler.class);
        if (handler == null) {
            ctx.tellFailure(msg, new IllegalStateException("未注入 SinkHandler"));
            return;
        }
        handler.handle(TARGET, msg);
        ctx.tellSuccess(msg);
    }
}
