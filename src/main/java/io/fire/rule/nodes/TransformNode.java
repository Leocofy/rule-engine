package io.fire.rule.nodes;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.*;
import io.fire.rule.spi.IExpressionEngine;

/**
 * 转换节点：改写 payload 与 metadata。
 *
 * <p>接入点：把你已有的 <b>Jsonata 字段映射引擎</b>实现为
 * {@link IExpressionEngine} 后，这个节点就具备"配置化映射任意厂商设备数据"的能力，
 * 新增设备类型无需改代码。
 */
@NodeDefinition(type = "transform", category = "transformation",
        description = "转换消息负载与元数据")
public class TransformNode implements RuleNode {

    private String expr;

    @Override
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {
        this.expr = config.get("expr");
    }

    @Override
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        String newPayload = ctx.expressionEngine().transform(expr, msg);
        RuleMessage next = msg.transform()
                .payload(newPayload)
                .copyMetaData(msg.getMetaData())
                .build();
        ctx.tellSuccess(next);
    }
}
