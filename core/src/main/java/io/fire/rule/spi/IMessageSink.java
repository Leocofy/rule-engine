package io.fire.rule.spi;

import io.fire.rule.model.RuleMessage;

import java.util.Set;

/**
 * 跨节点 / 跨实例的消息投递抽象。
 *
 * <p>消息在同一 JVM 内流转时走内存路由（零序列化开销）；
 * 需要跨服务节点时（按 originator 分区后，下游链可能归属别的实例）
 * 必须经由此接口落队列，否则服务重启即丢。
 *
 * <p>这是很多自研引擎上线水平扩展时才补的一层——建议一开始就把口子留好。
 */
public interface IMessageSink {

    void send(String chainId, String nodeId, RuleMessage msg, Set<String> relations);
}
