package io.fire.rule.example.node;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.IExpressionEngine;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.config.NodeComponent;
import io.fire.rule.spi.RuleNode;
import org.springframework.stereotype.Component;

/**
 * 过滤节点：按条件把消息分到 True / False 两条关系。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@NodeComponent(type = "filter", category = "filter",
        description = "按条件分流到 True / False",
        relations = {"True", "False"})
public class FilterNode implements RuleNode {

    @Override
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        IExpressionEngine engine = ctx.expressionEngine();
        String expr = ctx.nodeConfig().getString("expr");
        boolean pass = engine.evaluate(expr, msg);
        ctx.tellNext(msg, pass ? "True" : "False");
    }
}
