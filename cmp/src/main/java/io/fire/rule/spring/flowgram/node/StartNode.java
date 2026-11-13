package io.fire.rule.spring.flowgram.node;

import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.spring.annotation.NodeComponent;
import io.fire.rule.spring.base.SampleRuleNode;
import io.fire.rule.spring.flowgram.WorkflowConstants;
import io.fire.rule.spring.flowgram.node.config.BaseConfig;
import io.fire.rule.spring.flowgram.workflow.WorkflowSchema;

/**
 * 内置 Start 节点：唯一入口，把工作流入参（{@code entry}）写入命名空间 {@code outputs[startId]}，
 * 使下游可用 {@code ref:[startId, ...]} 引用。
 *
 * <p>引擎级控制流节点，非 {@code @NodeComponent}
 * 以单例提供，装载器直接挂到链上。
 *
 * @author rule-engine
 */
@NodeComponent(type = WorkflowConstants.TYPE_START, name = "开始", category = WorkflowSchema.CONTROL_TYPE,
        order = Integer.MIN_VALUE)
public final class StartNode extends SampleRuleNode<BaseConfig> {

    @Override
    protected void onMsg0(INodeContext ctx, BaseConfig config) throws Exception {
        ctx.emit(ctx.state().entry());
        ctx.next();
    }

}
