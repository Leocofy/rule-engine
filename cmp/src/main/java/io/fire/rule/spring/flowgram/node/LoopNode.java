package io.fire.rule.spring.flowgram.node;

import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.spring.annotation.NodeComponent;
import io.fire.rule.spring.base.SampleRuleNode;
import io.fire.rule.spring.flowgram.WorkflowConstants;
import io.fire.rule.spring.flowgram.node.config.LoopConfig;
import io.fire.rule.spring.flowgram.workflow.WorkflowSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置 Loop 节点：解析 {@code data.loopFor} 为数组，逐轮把 {@code {item,index}} 写入迭代作用域命名空间，
 * 同步运行内联子链（{@code __blocksChainId}）并累积状态；跑完按 {@code data.loopOutputs} 回收产出写入
 * {@code outputs[<loopId>]}，携累积状态从默认端口继续。
 *
 * <p>循环语义完全在本节点内实现，core 只提供通用原语（{@link INodeContext#runSubChain} 同步跑子链、
 * {@link INodeContext#next(ExecutionState, String)} 按显式状态路由）。循环配置（loopFor/loopOutputs/blocks）
 * 的后续变更只影响本类与装载器，不再触及 core 执行层。
 *
 * @author rule-engine
 */
@NodeComponent(type = WorkflowConstants.TYPE_LOOP, name = "循环", category = WorkflowSchema.CONTROL_TYPE, order = -999)
public final class LoopNode extends SampleRuleNode<LoopConfig> {

    @Override
    protected void onMsg0(INodeContext ctx, LoopConfig config) throws Exception {
        Object loopForValue = ctx.resolveValue(config == null ? null : config.getLoopFor());
        List<Object> items = new ArrayList<>();
        if (loopForValue instanceof List) {
            items.addAll(((List<?>) loopForValue));
        } else if (loopForValue != null) {
            items.add(loopForValue);
        }
        String blocksChainId = ctx.nodeConfig().getString(WorkflowConstants.BLOCKS_CHAIN_ID);
        String localsKey = WorkflowConstants.localsScope(ctx.getNodeId());

        ExecutionState acc = ctx.state();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> locals = new LinkedHashMap<>();
            locals.put("item", items.get(i));
            locals.put("index", i);
            acc = acc.withOutput(localsKey, locals).withOutput(WorkflowConstants.CURRENT_LOCALS, locals);
            if (blocksChainId != null) {
                acc = ctx.runSubChain(blocksChainId, acc);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (config != null && config.getLoopOutputs() != null) {
            config.getLoopOutputs().forEach((k, value) -> {
                Object o = ctx.resolveValue(value);
                ctx.withInput(k, o);
                result.put(k, o);
            });
        }

        ctx.emit(result);
        ctx.next(acc, INodeContext.PORT_DEFAULT);
    }

}
