package io.fire.rule.example.config;

import io.fire.rule.example.source.PullSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Example 配置：装配演示用的 PullSource 消息源。
 *
 * <p>通过 {@code example.enabled=false} 可禁用，此时 starter 自动配置
 * 会使用默认的 {@link io.fire.rule.runtime.InMemoryMessageSource}。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@Configuration
@ConditionalOnProperty(name = "example.enabled", havingValue = "true", matchIfMissing = true)
public class ExampleConfig {

    @Bean(destroyMethod = "destroy")
    public PullSource pullSource() {
        return new PullSource();
    }
}
