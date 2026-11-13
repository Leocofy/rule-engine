package io.fire.rule.core.config;

import io.fire.rule.core.model.NodeConfig;

/**
 * 节点配置绑定契约：把 DSL 的原始配置（含 IFlowValue）解析后绑定为强类型配置对象。
 *
 * <p>core 只定义契约，具体 DSL 语义（inputsValues / conditions / loopFor）由集成层实现。
 */
@FunctionalInterface
public interface INodeConfigBinder {

    <T> T bind(NodeConfig config, Class<T> type);
}