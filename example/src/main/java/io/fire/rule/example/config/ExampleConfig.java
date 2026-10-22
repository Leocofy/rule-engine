package io.fire.rule.example.config;

import io.fire.rule.example.source.CallBackSource;
import io.fire.rule.example.source.CompositeMessageSource;
import io.fire.rule.example.source.PullSource;
import io.fire.rule.example.support.TelemetrySimulator;
import io.fire.rule.runtime.MessageSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Example 配置：装配演示用的消息源。
 *
 * <p>{@code example.enabled=true} 时同时启用拉取式模拟网关与 HTTP 回调入口，
 * 由 {@link CompositeMessageSource} 合并成引擎消费通道；
 * 置为 {@code false} 后仅保留回调入口（CallBackSource）。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@Configuration
@ConditionalOnProperty(name = "example.enabled", havingValue = "true")
public class ExampleConfig {

    @Bean(initMethod = "init", destroyMethod = "destroy")
    public PullSource pullSource(TelemetrySimulator simulator,
                                 @Value("${example.pull-interval-seconds:5}") long intervalSeconds) {
        return new PullSource(simulator, intervalSeconds);
    }

    /**
     * 引擎消费通道：同时承接接口回调与模拟网关流量。
     *
     * <p>标记 {@link Primary} 是因为容器里同时存在 CallBackSource / PullSource，
     * 引擎按类型注入 MessageSource 时需要唯一候选。
     */
    @Bean
    @Primary
    public MessageSource engineMessageSource(CallBackSource callBackSource, PullSource pullSource) {
        return new CompositeMessageSource(callBackSource, pullSource);
    }
}
