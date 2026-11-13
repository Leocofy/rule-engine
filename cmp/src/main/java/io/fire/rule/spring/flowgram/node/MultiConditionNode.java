package io.fire.rule.spring.flowgram.node;

import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.spring.annotation.NodeComponent;
import io.fire.rule.spring.base.SampleRuleNode;
import io.fire.rule.spring.flowgram.WorkflowConstants;
import io.fire.rule.spring.flowgram.node.config.ConditionConfig;
import io.fire.rule.spring.flowgram.node.config.MultiConditionConfig;
import io.fire.rule.spring.flowgram.workflow.OperatorEvaluator;
import io.fire.rule.spring.flowgram.workflow.WorkflowSchema;

/**
 * 多条件分支：每个 branch 对应一个输出端口 {@code branch.<index>}，支持组内 and/or。
 */
@NodeComponent(type = WorkflowConstants.TYPE_MULTI_CONDITION, name = "多条件分支",
        category = WorkflowSchema.CONTROL_TYPE, order = -997,
        description = "按分支条件执行，支持 and/or 组内组合")
public final class MultiConditionNode extends SampleRuleNode<MultiConditionConfig> {

    public static final String ELSE_PORT = "else";

    public static final String BRANCH_PORT_PREFIX = "branch.";

    @Override
    protected void onMsg0(INodeContext ctx, MultiConditionConfig config) throws Exception {
        if (config == null || config.getBranch() == null) {
            ctx.next(ELSE_PORT);
            return;
        }
        for (int i = 0; i < config.getBranch().size(); i++) {
            MultiConditionConfig.Branch branch = config.getBranch().get(i);
            if (branch == null || branch.getConditions() == null || branch.getConditions().isEmpty()) {
                continue;
            }
            if (matches(ctx, branch)) {
                ctx.next(BRANCH_PORT_PREFIX + i);
                return;
            }
        }
        ctx.next(ELSE_PORT);
    }

    private boolean matches(INodeContext ctx, MultiConditionConfig.Branch branch) {
        boolean and = !"or".equalsIgnoreCase(branch.getLogic());
        for (MultiConditionConfig.ConditionItem item : branch.getConditions()) {
            if (item == null || item.getValue() == null) {
                if (and) {
                    return false;
                }
                continue;
            }
            ConditionConfig.ConditionValue value = item.getValue();
            Object left = ctx.resolveValue(value.getLeft());
            Object right = ctx.resolveValue(value.getRight());
            boolean matched = OperatorEvaluator.test(value.getOperator(), left, right);
            if (and && !matched) {
                return false;
            }
            if (!and && matched) {
                return true;
            }
        }
        return and;
    }
}