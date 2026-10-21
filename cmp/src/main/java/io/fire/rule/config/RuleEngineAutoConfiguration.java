package io.fire.rule.config;

import io.fire.rule.RuleEngine;
import io.fire.rule.expression.AviatorExpressionEngine;
import io.fire.rule.nodes.SimpleExpressionEngine;
import io.fire.rule.observability.EngineObserver;
import io.fire.rule.runtime.InMemoryMessageSource;
import io.fire.rule.runtime.MessageSource;
import io.fire.rule.runtime.strategy.SequentialByKeySubmitStrategy;
import io.fire.rule.runtime.strategy.SubmitStrategy;
import io.fire.rule.spi.IExpressionEngine;
import io.fire.rule.spi.INodeRegistry;
import io.fire.rule.spi.NodeException;
import io.fire.rule.spi.NodeRegistry;
import io.fire.rule.util.JsonUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 规则引擎 Spring Boot 自动配置。
 *
 * <p>自动装配以下 Bean（均可用自定义实现覆盖）：
 * <ul>
 *   <li>{@link SpringNodeRegistry} —— 从 ApplicationContext 收集 {@code RuleNode} bean</li>
 *   <li>{@link IExpressionEngine} —— 默认 Aviator，可切换为 SimpleExpressionEngine</li>
 *   <li>{@link MessageSource} —— 默认 InMemoryMessageSource</li>
 *   <li>{@link EngineObserver} —— 默认 Stats 统计</li>
 *   <li>{@link SubmitStrategy} —— 默认 SequentialByKeySubmitStrategy（按 originator 串行）</li>
 *   <li>{@link RuleEngine} —— 引擎门面，自动加载规则链并启动</li>
 * </ul>
 *
 * <p>业务侧通过实现 {@link RuleEngineCustomizer} 注册为 Spring Bean，
 * 即可在引擎构建阶段注入 capability、自定义策略等。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@Configuration
@ConditionalOnClass(RuleEngine.class)
@ConditionalOnProperty(prefix = "rule.engine", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RuleEngineProperties.class)
public class RuleEngineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(INodeRegistry.class)
    public SpringNodeRegistry springNodeRegistry() {
        return new SpringNodeRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(IExpressionEngine.class)
    public IExpressionEngine expressionEngine(RuleEngineProperties props) {
        if ("simple".equalsIgnoreCase(props.getExpressionEngine())) {
            return new SimpleExpressionEngine();
        }
        return new AviatorExpressionEngine();
    }

    @Bean
    @ConditionalOnMissingBean(MessageSource.class)
    public InMemoryMessageSource ruleEngineMessageSource() {
        return new InMemoryMessageSource("main");
    }

    @Bean
    @ConditionalOnMissingBean(EngineObserver.class)
    public EngineObserver engineObserver() {
        return new EngineObserver.Stats();
    }

    @Bean
    @ConditionalOnMissingBean(SubmitStrategy.class)
    public SubmitStrategy submitStrategy() {
        return new SequentialByKeySubmitStrategy();
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean(RuleEngine.class)
    public RuleEngine ruleEngine(
            INodeRegistry nodeRegistry,
            IExpressionEngine expressionEngine,
            MessageSource messageSource,
            EngineObserver engineObserver,
            SubmitStrategy submitStrategy,
            RuleEngineProperties props,
            ObjectProvider<RuleEngineCustomizer> customizerProvider) throws NodeException {

        NodeRegistry registry = NodeRegistry.builder()
                .autoDiscover(true)
                .registry(nodeRegistry)
                .build();

        String chainJson = null;
        String defaultChainId = props.getDefaultChainId();
        if (props.getChainConfigPath() != null) {
            chainJson = readClasspathResource(props.getChainConfigPath());
            if (defaultChainId == null) {
                Map<String, Object> root = JsonUtil.parseObject(chainJson);
                defaultChainId = JsonUtil.asString(root.get("id"));
            }
        }

        RuleEngine.Builder builder = RuleEngine.builder()
                .registry(registry)
                .expressionEngine(expressionEngine)
                .source(messageSource)
                .observer(engineObserver)
                .submitStrategy(submitStrategy)
                .pollSize(props.getPollSize())
                .packTimeoutMs(props.getPackTimeoutMs());

        if (defaultChainId != null) {
            builder.defaultChainId(defaultChainId);
        }

        customizerProvider.orderedStream().forEach(c -> c.customize(builder));

        RuleEngine engine = builder.build();

        if (chainJson != null) {
            engine.loadChain(chainJson);
        }

        if (props.isAutoStart()) {
            engine.start();
        }

        return engine;
    }

    private String readClasspathResource(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream in = resource.getInputStream()) {
                return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法加载规则链配置: " + path, e);
        }
    }
}
