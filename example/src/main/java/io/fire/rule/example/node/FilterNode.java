package io.fire.rule.example.node;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.IExpressionEngine;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.NodeComponent;
import io.fire.rule.spi.NodeConfig;
import io.fire.rule.spi.NodeException;
import io.fire.rule.spi.RuleNode;

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

    private String expr;

    @Override
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {
        this.expr = config.get("expr");
        if (expr == null || expr.isEmpty()) {
            throw new NodeException("filter 节点缺少 expr 配置", true);
        }
    }

    @Override
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        IExpressionEngine engine = ctx.expressionEngine();
        boolean pass = engine.evaluate(expr, msg);
        ctx.tellNext(msg, pass ? "True" : "False");
    }
}
