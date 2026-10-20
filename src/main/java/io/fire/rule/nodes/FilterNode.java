package io.fire.rule.nodes;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.*;
import io.fire.rule.spi.IExpressionEngine;

/**
 * 过滤节点：按条件把消息分到 True / False 两条关系。
 *
 * <p>节点本身不知道下游是谁——它只声明"这条消息属于 True"。
 * 这就是"关系名代替 if/else"的实际样子。
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
