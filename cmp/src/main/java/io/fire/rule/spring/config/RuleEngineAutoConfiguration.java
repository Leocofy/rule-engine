package io.fire.rule.spring.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.core.RuleEngine;
import io.fire.rule.core.chain.ChainExecutor;
import io.fire.rule.core.config.IChainLoader;
import io.fire.rule.core.config.INodeConfigBinder;
import io.fire.rule.core.model.NodeConfig;
import io.fire.rule.core.observability.EngineObserver;
import io.fire.rule.core.runtime.InMemoryMessageSource;
import io.fire.rule.core.runtime.MessageSource;
import io.fire.rule.core.runtime.strategy.SequentialByKeySubmitStrategy;
import io.fire.rule.core.runtime.strategy.SubmitStrategy;
import io.fire.rule.core.spi.IExpressionEngine;
import io.fire.rule.core.spi.INodeRegistry;
import io.fire.rule.core.store.IChainStore;
import io.fire.rule.core.store.InMemoryChainStore;
import io.fire.rule.spring.expression.AviatorExpressionEngine;
import io.fire.rule.spring.flowgram.workflow.value.ValueResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

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
 * <p>
 * 即可在引擎构建阶段注入 capability、自定义策略等。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@AutoConfiguration
@ConditionalOnClass(RuleEngine.class)
@ConditionalOnProperty(prefix = "rule.engine", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RuleEngineProperties.class)
@Import(NodeComponentRegistrar.class)
public class RuleEngineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(INodeRegistry.class)
    public SpringNodeRegistry springNodeRegistry() {
        return new SpringNodeRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(INodeConfigBinder.class)
    public INodeConfigBinder nodeConfigBinder(ObjectMapper mapper) {
        return new INodeConfigBinder() {
            @Override
            public <T> T bind(NodeConfig config, Class<T> type) {
                return mapper.convertValue(config.props(), type);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(IExpressionEngine.class)
    public IExpressionEngine expressionEngine() {
        return new AviatorExpressionEngine();
    }

    @Bean
    @ConditionalOnMissingBean(MessageSource.class)
    public InMemoryMessageSource ruleEngineMessageSource() {
        return new InMemoryMessageSource("main");
    }

    @Bean
    @ConditionalOnMissingBean(SubmitStrategy.class)
    public SubmitStrategy submitStrategy() {
        return new SequentialByKeySubmitStrategy();
    }

    @Bean
    @ConditionalOnMissingBean(IChainStore.class)
    public IChainStore chainStore(IChainLoader chainLoader) {
        return new InMemoryChainStore(chainLoader);
    }

    @Bean
    @ConditionalOnMissingBean(ChainExecutor.class)
    public ChainExecutor.Builder chainExecutorBuilder(
            IChainStore chainStore,
            IExpressionEngine expressionEngine,
            EngineObserver engineObserver,
            INodeConfigBinder configBinder
    ) {
        return ChainExecutor.builder()
                .expressionEngine(expressionEngine)
                .chainStore(chainStore)
                .observer(engineObserver)
                .valueResolver(new ValueResolver())
                .configBinder(configBinder);
    }

    @Bean
    @ConditionalOnMissingBean(RuleEngine.Builder.class)
    public RuleEngine.Builder engineBuilder(
            INodeRegistry nodeRegistry,
            IExpressionEngine expressionEngine,
            MessageSource messageSource,
            EngineObserver engineObserver,
            SubmitStrategy submitStrategy,
            INodeConfigBinder configBinder,
            RuleEngineProperties props) {
        return RuleEngine.builder()
                .registry(nodeRegistry)
                .expressionEngine(expressionEngine)
                .source(messageSource)
                .observer(engineObserver)
                .submitStrategy(submitStrategy)
                .configBinder(configBinder)
                .pollSize(props.getPollSize())
                .packTimeoutMs(props.getPackTimeoutMs());
    }
}
