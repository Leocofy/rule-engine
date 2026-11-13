package io.fire.rule.example.node;

import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.core.spi.RuleNode;
import io.fire.rule.example.config.NodeCategory;
import io.fire.rule.spring.annotation.NodeComponent;

/**
 * 异步外部调用节点：示范"耗时 I/O 必须外置线程池"。
 *
 * <p>业务侧实现 {@link SinkHandler} 并通过 {@code RuleEngineCustomizer}
 * 注入 capability。
 *
 * @author Cai FeiYue
 * @date 2026-10-21
 */
//@NodeComponent(type = "asyncSink", category = NodeCategory.BUSINESS,
//        description = "异步调用外部系统（落库/HTTP/通知）")
public class AsyncSinkNode implements RuleNode {

    /**
     * 外部系统写入能力：target 用于区分落库 / 通知 / 归档等不同目的端，
     * 由各 sink 节点以常量传入，避免依赖链中节点配置（引擎按 type 复用实例）。
     */
    public interface SinkHandler {
        void handle(String target, ExecutionState state) throws Exception;
    }

    @Override
    public void onMsg(final INodeContext ctx) throws Exception {
        final SinkHandler handler = ctx.capability(SinkHandler.class);
        if (handler == null) {
            ctx.fail(new IllegalStateException("未注入 SinkHandler"));
            return;
        }
        Object targetVal = ctx.nodeConfig().get("target");
        final String target = targetVal == null ? "default" : String.valueOf(targetVal);
        final ExecutionState state = ctx.state();
        ctx.asyncExecutor().execute(() -> {
            try {
                handler.handle(target, state);
                ctx.ack();
            } catch (Exception e) {
                ctx.fail(e);
            }
        });
    }
}
