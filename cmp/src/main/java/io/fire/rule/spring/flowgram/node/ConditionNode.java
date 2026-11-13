package io.fire.rule.spring.flowgram.node;

import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.spring.annotation.NodeComponent;
import io.fire.rule.spring.base.SampleRuleNode;
import io.fire.rule.spring.flowgram.WorkflowConstants;
import io.fire.rule.spring.flowgram.node.config.ConditionConfig;
import io.fire.rule.spring.flowgram.workflow.OperatorEvaluator;
import io.fire.rule.spring.flowgram.workflow.WorkflowSchema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置 Condition 节点：命中首个 {@code conditions[].key} 作为输出端口 {@code tellNext(msg, key)}；
 * 全不命中走 {@link #ELSE_PORT}。算子求值用 {@link OperatorEvaluator}，不依赖表达式引擎。
 *
 * <p>读取 {@code data.conditions}，每项形如：
 * <pre>
 * { "key": "端口ID",
 *   "value": { "left": IFlowValue, "operator": "eq", "right": IFlowValue } }
 * </pre>
 * {@code left}/{@code right} 是 IFlowValue，运行期先解析成真实值再比较。
 *
 * @author rule-engine
 */
@NodeComponent(type = WorkflowConstants.TYPE_CONDITION, name = "条件", category = WorkflowSchema.CONTROL_TYPE, order = -998)
public final class ConditionNode extends SampleRuleNode<ConditionConfig> {

    /**
     * 全部条件不命中时的兜底端口。
     */
    public static final String ELSE_PORT = "else";

    @Override
    protected void onMsg0(INodeContext ctx, ConditionConfig config) throws Exception {
        if (config == null || config.getConditions() == null) {
            ctx.next(ELSE_PORT);
            return;
        }
        for (ConditionConfig.ConditionItem item : config.getConditions()) {
            if (item == null || item.getValue() == null) {
                continue;
            }
            ConditionConfig.ConditionValue value = item.getValue();
            Object left = ctx.resolveValue(value.getLeft());
            Object right = ctx.resolveValue(value.getRight());

            if (OperatorEvaluator.test(value.getOperator(), left, right)) {
                String key = item.getKey();
                ctx.next(key == null || key.isEmpty() ? ELSE_PORT : key);
                return;
            }
        }
        ctx.next(ELSE_PORT);
    }

}
