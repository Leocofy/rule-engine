package io.fire.rule.spi;

import io.fire.rule.model.RuleMessage;

/**
 * 规则节点 SPI：引擎的最小扩展单元。
 *
 * <p><b>铁律：节点必须无状态。</b>
 * 状态只能放在消息 metadata 或外部存储中。原因有二：
 * <ul>
 *   <li>水平扩展时，节点实例可能分布在不同 JVM</li>
 *   <li>批次重试时，内存状态已丢失（ThingsBoard 的 Delay 节点正因内存状态被标记废弃）</li>
 * </ul>
 *
 * <p><b>实现约定</b>：
 * <ul>
 *   <li>{@code init} 做重活：编译脚本、建连接、解析配置</li>
 *   <li>{@code onMsg} 只做处理，且<b>每个分支都要有出口</b>——
 *       漏掉 tellSuccess/tellFailure/ack 消息会一直挂在批次里直到超时</li>
 *   <li>耗时 I/O 必须提交到 {@link INodeContext#asyncExecutor()}，不要占用调度线程</li>
 * </ul>
 */
public interface RuleNode {

    void init(INodeContext ctx, NodeConfig config) throws NodeException;

    void onMsg(INodeContext ctx, RuleMessage msg) throws Exception;

    default void destroy() {
    }

    /**
     * 配置版本迁移：规则链以 JSON 持久化，节点升级后旧配置需要兼容。
     * 默认原样返回表示不支持迁移；建议从第一版就实现，后期补代价很高。
     */
    default NodeConfig upgrade(int fromVersion, NodeConfig oldConfig) throws NodeException {
        return oldConfig;
    }
}
