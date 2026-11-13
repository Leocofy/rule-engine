package io.fire.rule.spring.flowgram.node;

import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.spring.annotation.NodeComponent;
import io.fire.rule.spring.base.SampleRuleNode;
import io.fire.rule.spring.flowgram.WorkflowConstants;
import io.fire.rule.spring.flowgram.node.config.VariableConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 变量赋值/定义节点：把 {@code data.assign} 的右侧表达式解析为真实值，写入本节点 outputs。
 */
@NodeComponent(type = WorkflowConstants.TYPE_VARIABLE, name = "变量赋值", category = "基础",
        order = 11, description = "声明或更新命名变量，供下游节点引用")
public final class VariableNode extends SampleRuleNode<VariableConfig> {

    @Override
    protected void onMsg0(INodeContext ctx, VariableConfig config) throws Exception {
        Map<String, Object> outputs = new LinkedHashMap<>();
        if (config != null && config.getAssign() != null) {
            for (VariableConfig.AssignItem item : config.getAssign()) {
                if (item == null || item.getLeft() == null || item.getLeft().trim().isEmpty()) {
                    continue;
                }
                outputs.put(item.getLeft(), ctx.resolveValue(item.getRight()));
            }
        }
        ctx.emit(outputs);
        ctx.next();
    }
}