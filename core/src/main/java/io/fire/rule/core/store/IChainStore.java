package io.fire.rule.core.store;

import io.fire.rule.core.chain.RuleChain;
import io.fire.rule.core.spi.NodeException;

import java.util.Collection;

/**
 * Description: IChainStore 规则链存储
 *
 * @author Cai FeiYue
 * @date 2026/10/23
 */
public interface IChainStore {

    RuleChain registerChain(Object chainData) throws NodeException;

    RuleChain deleteChain(String chainId);

    Object getChainRawData(String chainId);

    RuleChain getChain(String chainId);

    Collection<Object> getChains();

}