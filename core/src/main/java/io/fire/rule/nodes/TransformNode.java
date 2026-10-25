package io.fire.rule.nodes;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.IExpressionEngine;
import io.fire.rule.spi.INodeContext;
import io.fire.rule.spi.RuleNodeDefinition;

/**
 * 转换节点：改写 payload 与 metadata。
 *
 * <p>接入点：把你已有的 <b>Jsonata 字段映射引擎</b>实现为
 * {@link IExpressionEngine} 后，这个节点就具备"配置化映射任意厂商设备数据"的能力，
 * 新增设备类型无需改代码。
 */
public class TransformNode implements RuleNodeDefinition {

    @Override
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        String expr = ctx.nodeConfig().getString("expr");

        String newPayload = ctx.expressionEngine().transform(expr, msg);
        RuleMessage next = msg.transform()
                .payload(newPayload)
                .copyMetaData(msg.getMetaData())
                .build();
        ctx.tellSuccess(next);
    }

    @Override
    public String type() {
        return "transform";
    }

    @Override
    public String title() {
        return "消息转换";
    }

    @Override
    public String category() {
        return "transformation";
    }

    @Override
    public String description() {
        return "转换消息负载与元数据";
    }

    @Override
    public String[] relations() {
        return new String[0];
    }

    @Override
    public boolean customRelations() {
        return false;
    }

    @Override
    public String version() {
        return "1.0";
    }
}
