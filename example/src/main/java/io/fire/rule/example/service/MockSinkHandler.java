package io.fire.rule.example.service;

import io.fire.rule.example.node.AsyncSinkNode;
import io.fire.rule.model.RuleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 示例落库处理器：模拟异步写入外部系统。
 *
 * <p>自动被引擎 capability 机制注入到 {@link AsyncSinkNode} 节点中。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@Component
public class MockSinkHandler implements AsyncSinkNode.SinkHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MockSinkHandler.class);

    @Override
    public void handle(RuleMessage msg) {
        LOG.info("已落库: type={} originator={} payload={}", msg.getType(), msg.getOriginator(), msg.getPayload());
    }
}
