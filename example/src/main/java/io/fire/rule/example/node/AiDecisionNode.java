package io.fire.rule.example.node;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.config.NodeComponent;
import io.fire.rule.spi.NodeConfig;
import io.fire.rule.spi.NodeException;
import io.fire.rule.spi.RuleNode;
import org.springframework.stereotype.Component;

/**
 * AI 判定节点：把智能体的结论直接当作关系名输出。
 *
 * <p>业务侧实现 {@link DecisionService} 并通过 {@code RuleEngineCustomizer}
 * 注入 capability，引擎不绑定任何具体模型。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@NodeComponent(type = "aiDecision", category = "ai",
        description = "调用 AI 智能体判定，结论作为关系名输出",
        customRelations = true)
public class AiDecisionNode implements RuleNode {

    public interface DecisionService {
        String decide(String agent, RuleMessage msg) throws Exception;
    }

    private String agent;
    private long latencyMs = 200L;

    @Override
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {
        this.agent = config.getOrDefault("agent", "default");
        this.latencyMs = config.getInt("latencyMs", 200);
        // 压测开关：-Dexample.ai.latency-ms=0 可去掉模拟耗时，用于测引擎本身的上限
        String override = System.getProperty("example.ai.latency-ms");
        if (override != null && !override.trim().isEmpty()) {
            this.latencyMs = Long.parseLong(override.trim());
        }
    }

    @Override
    public void onMsg(final INodeContext ctx, final RuleMessage msg) throws Exception {
        final DecisionService service = ctx.capability(DecisionService.class);
        if (service == null) {
            ctx.tellFailure(msg, new IllegalStateException("未注入 DecisionService"));
            return;
        }
        ctx.asyncExecutor().execute(() -> {
            try {
                if (!msg.isValid()) {
                    return;
                }
                if (latencyMs > 0) {
                    Thread.sleep(latencyMs);
                }
                String relation = service.decide(agent, msg);
                if (relation == null || relation.isEmpty()) {
                    ctx.tellSuccess(msg);
                    return;
                }
                ctx.tellNext(msg, relation);
            } catch (Exception e) {
                ctx.tellFailure(msg, e);
            }
        });
    }
}
