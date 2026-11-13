package io.fire.rule.example.node;

import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.core.spi.RuleNode;
import io.fire.rule.core.util.JsonUtil;
import io.fire.rule.spring.flowgram.workflow.value.ValueResolver;
import io.fire.rule.example.config.NodeCategory;
import io.fire.rule.spring.annotation.NodeComponent;

import java.util.Collections;
import java.util.Map;

/**
 * 赋值节点（assign）：把上游产出重映射 / 拼装成新的命名空间产出。
 *
 * <p>与固定产出的业务节点不同，本节点的产出键完全由设计器表单决定——
 * {@code data.outputs} 是一张 {@code Record<name, IFlowValue>} 表，值多为 {@code ref}
 * 指向上游节点产出。运行期用 {@link ValueResolver} 逐字段解析成真实值后写入
 * {@code outputs[<nodeId>]}，供下游按名引用。产出键完全由前端设计器控制，
 * 编辑器据用户填写的键 + 值类型派生可选产出。
 *
 * @author Cai FeiYue
 * @date 2026-11-04
 */
//@NodeComponent(type = "assign", name = "赋值", category = NodeCategory.TRANSFORM,
//        description = "把上游产出重映射/拼装为新的命名字段（值多为引用）")
public class AssignNode implements RuleNode {

    @Override
    public void onMsg(final INodeContext ctx) throws Exception {
        Object outputsRaw = ctx.nodeConfig().get("outputs");
        Map<String, Object> record = outputsRaw instanceof Map
                ? JsonUtil.asObject(outputsRaw) : Collections.<String, Object>emptyMap();
//        Map<String, Object> resolved = ValueResolver.resolveValue(record, ctx.state(), ctx.expressionEngine());
//        ctx.emit(resolved);
        ctx.next();
    }
}
