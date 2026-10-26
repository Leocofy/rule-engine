package io.fire.rule.config;

import io.fire.rule.chain.RuleChain;
import io.fire.rule.spi.NodeException;

/**
 * Description: 解析链加载器
 *
 * @author Cai FeiYue
 * @date 2026/10/23
 */
@FunctionalInterface
public interface IChainLoader {

    RuleChain load(Object chain) throws NodeException;

}
