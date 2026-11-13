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
 * 定义节点（define）：声明一组带显类型常量（或少量表达式）作为命名空间产出。
 *
 * <p>与 {@link AssignNode} 运行期完全同构——都读 {@code data.outputs}
 * （{@code Record<name, IFlowValue>}）并用 {@link ValueResolver} 解析后写入
 * {@code outputs[<nodeId>]}；区别只在设计语义：define 的值多为 {@code constant}
 * （可带显式类型），用于给下游提供常量参数 / 阈值等，assign 的值多为 {@code ref}。
 *
 * @author Cai FeiYue
 * @date 2026-11-04
 */
//@NodeComponent(type = "define", name = "定义", category = NodeCategory.TRANSFORM,
//        description = "声明一组带类型的常量/表达式作为命名字段（值多为常量）")
public class DefineNode implements RuleNode {

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
