package io.fire.rule.example.config;

import io.fire.rule.core.config.IChainLoader;
import io.fire.rule.core.store.IChainStore;
import io.fire.rule.core.store.InMemoryChainStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Description: NodeConfig
 *
 * @author Cai FeiYue
 * @date 2026/10/27
 */
@Configuration
public class NodeConfig {

    @Bean
    public IChainStore chainStore(IChainLoader chainLoader) {
        return new InMemoryChainStore(chainLoader, "E:\\rule-engine\\example");
    }

}
