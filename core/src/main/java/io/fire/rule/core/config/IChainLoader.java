package io.fire.rule.core.config;

import io.fire.rule.core.chain.RuleChain;
import io.fire.rule.core.spi.NodeException;

/**
 * Description: 解析链加载器
 *
 * <p>把链的原始描述（Map/JSON 等）解析为可执行的 {@link RuleChain}。具体 DSL（如 flowgram 的
 * {@code {nodes[], edges[]}}）由集成层实现解析；core 只定义加载契约，不绑定链格式。
 *
 * @author Cai FeiYue
 * @date 2026/10/23
 */
@FunctionalInterface
public interface IChainLoader {

    RuleChain load(Object chain) throws NodeException;

}
