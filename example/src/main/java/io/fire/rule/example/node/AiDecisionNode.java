package io.fire.rule.example.node;

import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.core.spi.RuleNode;
import io.fire.rule.example.config.NodeCategory;
import io.fire.rule.spring.annotation.NodeComponent;

import java.util.LinkedHashMap;

/**
 * AI 判定节点：把智能体的结论直接当作关系名输出。
 *
 * <p>业务侧实现 {@link DecisionService} 并通过 {@code RuleEngineCustomizer}
 * 注入 capability，引擎不绑定任何具体模型。
 *
 * @author Cai FeiYue
 * @date 2026-10-21
 */
//@NodeComponent(type = "aiDecision", category = NodeCategory.AGENT,
//        description = "调用 AI 智能体判定，结论作为关系名输出")
public class AiDecisionNode implements RuleNode {

    public interface DecisionService {
        String decide(String agent, ExecutionState state) throws Exception;
    }

    @Override
    public void onMsg(final INodeContext ctx) throws Exception {
        final DecisionService service = ctx.capability(DecisionService.class);
        if (service == null) {
            ctx.fail(new IllegalStateException("未注入 DecisionService"));
            return;
        }
        java.util.Map<String, Object> inputs = new LinkedHashMap<>();
        Object agentVal = inputs.get("agent");
        final String agent = agentVal == null ? "default" : String.valueOf(agentVal);
        long latencyMs = toLong(inputs.get("latencyMs"), 200);
        // 压测开关：-Dexample.ai.latency-ms=0 可去掉模拟耗时，用于测引擎本身的上限
        String override = System.getProperty("example.ai.latency-ms");
        if (override != null && !override.trim().isEmpty()) {
            latencyMs = Long.parseLong(override.trim());
        }
        final long finalLatencyMs = latencyMs;
        final ExecutionState state = ctx.state();
        ctx.asyncExecutor().execute(() -> {
            try {
                if (!ctx.isValid()) {
                    return;
                }
                if (finalLatencyMs > 0) {
                    Thread.sleep(finalLatencyMs);
                }
                String relation = service.decide(agent, state);
                if (relation == null || relation.isEmpty()) {
                    ctx.next();
                    return;
                }
                ctx.next(relation);
            } catch (Exception e) {
                ctx.fail(e);
            }
        });
    }

    /** 把解析后的输入值（Number/String/null）转成 long，失败取默认值。 */
    private static long toLong(final Object v, final long def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
