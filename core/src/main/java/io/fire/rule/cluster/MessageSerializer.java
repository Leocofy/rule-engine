package io.fire.rule.cluster;

import io.fire.rule.model.RuleMessage;

import java.util.Set;

/**
 * 消息序列化 SPI：RuleMessage <-> 字节流。
 *
 * <p>做成接口是为了不绑定具体协议：
 * 内置 JSON 实现零依赖可直接用，
 * 生产环境可换成 Protobuf / Kryo 以压缩体积、提升性能。
 */
public interface MessageSerializer {

    byte[] serialize(RuleMessage msg, Set<String> relations);

    TransportMessage deserialize(byte[] data);

    /**
     * 把传输对象还原成引擎内部消息。
     * 与 deserialize 分开是因为还原需要构造 RuleMessage（含回调语义），
     * 而纯粹的传输解析不应依赖引擎模型。
     */
    default RuleMessage toRuleMessage(TransportMessage t) {
        throw new UnsupportedOperationException("该序列化器不支持还原为 RuleMessage");
    }
}
