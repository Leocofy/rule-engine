package io.fire.rule.spring.flowgram.node;

import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.spring.annotation.NodeComponent;
import io.fire.rule.spring.base.SampleRuleNode;
import io.fire.rule.spring.flowgram.WorkflowConstants;
import io.fire.rule.spring.flowgram.node.config.BaseConfig;
import io.fire.rule.spring.flowgram.workflow.WorkflowSchema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置 End 节点：到达即完成。按 {@code data.inputsValues} 汇总工作流输出写入命名空间
 * {@code outputs[endId]}，再 {@code ack} 收口当前批次。
 *
 * @author rule-engine
 */
@NodeComponent(type = WorkflowConstants.TYPE_END, name = "结束", category = WorkflowSchema.CONTROL_TYPE, order = Integer.MAX_VALUE)
public final class EndNode extends SampleRuleNode<BaseConfig> {

    @Override
    protected void onMsg0(INodeContext ctx, BaseConfig config) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        if (config.getInputsValues() != null) {
            config.getInputsValues().forEach((name, value) -> {
                Object v = ctx.resolveValue(value);
                ctx.withInput(name, v);
                result.put(name, v);
            });
        }
        ctx.emit(result);
        ctx.ack();
    }
}
