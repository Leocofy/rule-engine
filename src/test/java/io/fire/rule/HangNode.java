package io.fire.rule;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.*;

/** 永远不返回，用于验证批次超时 */
@NodeDefinition(type = "hang", category = "debug")
public class HangNode implements RuleNode {
    public void init(INodeContext ctx, NodeConfig config) throws NodeException {}
    public void onMsg(INodeContext ctx, RuleMessage msg) throws Exception {
        // 异步挂起：不调用任何出口，模拟外部调用卡死
        ctx.asyncExecutor().execute(new Runnable() {
            public void run() {
                try { Thread.sleep(60000); } catch (InterruptedException e) { }
            }
        });
    }
}
