package io.fire.rule.nodes;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.NodeConfig;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.NodeDefinition;
import io.fire.rule.spi.NodeException;
import io.fire.rule.spi.RuleNode;

import java.util.Set;

/**
 * AI 判定节点：<b>把智能体的结论直接当作关系名输出</b>。
 *
 * <p>这是把大模型接入规则链最自然的方式——
 * 你的消防 Agent（智策巡卫 / 智巡助手 / 风险智判 / 慧眼智析）
 * 本来就输出分类结论（有隐患 / 无隐患 / 需现场复核），
 * 正好作为关系名使用，节点里一行 if/else 都不用写。
 *
 * <p><b>两个硬性约束</b>：
 * <ol>
 *   <li>AI 调用必须提交到 {@code asyncExecutor}，绝不能占调度线程</li>
 *   <li>回调里要先检查 {@code msg.isValid()}，批次已超时就别浪费算力</li>
 *   <li>百万设备场景必须"规则预筛 + AI 抽稀"，不能每条消息都调模型</li>
 * </ol>
 */
@NodeDefinition(type = "aiDecision", category = "ai",
        description = "调用 AI 智能体判定，结论作为关系名输出",
        customRelations = true)
public class AiDecisionNode implements RuleNode {

    /** 业务侧实现该接口并注入 capability，引擎不绑定任何具体模型。 */
    public interface DecisionService {
        String decide(String agent, RuleMessage msg) throws Exception;
    }

    private String agent;
    private Set<String> fallbackRelations = java.util.Collections.singleton("Success");

    @Override
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {
        this.agent = config.getOrDefault("agent", "default");
    }

    @Override
    public void onMsg(final INodeContext ctx, final RuleMessage msg) throws Exception {
        final DecisionService service = ctx.capability(DecisionService.class);
        if (service == null) {
            ctx.tellFailure(msg, new IllegalStateException("未注入 DecisionService"));
            return;
        }
        ctx.asyncExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!msg.isValid()) {
                        return; // 批次已超时/取消
                    }
                    String relation = service.decide(agent, msg);
                    if (relation == null || relation.isEmpty()) {
                        ctx.tellNext(msg, fallbackRelations);
                        return;
                    }
                    ctx.tellNext(msg, relation);
                } catch (Exception e) {
                    ctx.tellFailure(msg, e);
                }
            }
        });
    }
}
