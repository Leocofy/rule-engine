package io.fire.rule.example.service;

import io.fire.rule.example.node.AiDecisionNode;
import io.fire.rule.model.RuleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 示例 AI 判定服务：按 payload 内容给出中文结论，结论直接作为关系名。
 *
 * <p>自动被引擎 capability 机制注入到 {@link AiDecisionNode} 节点中。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@Component
public class MockDecisionService implements AiDecisionNode.DecisionService {

    private static final Logger LOG = LoggerFactory.getLogger(MockDecisionService.class);

    @Override
    public String decide(String agent, RuleMessage msg) {
        String payload = msg.getPayload();
        LOG.info("AI 判定 [agent={}]: {}", agent, payload);
        if (payload != null && (payload.contains("\"95\"") || payload.contains("95"))) {
            return "有隐患";
        }
        return "无隐患";
    }
}
