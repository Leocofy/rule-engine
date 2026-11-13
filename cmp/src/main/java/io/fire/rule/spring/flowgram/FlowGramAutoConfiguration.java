package io.fire.rule.spring.flowgram;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.core.RuleEngine;
import io.fire.rule.core.chain.ChainExecutor;
import io.fire.rule.core.config.IChainLoader;
import io.fire.rule.core.config.INodeConfigBinder;
import io.fire.rule.core.observability.EngineObserver;
import io.fire.rule.core.spi.INodeRegistry;
import io.fire.rule.core.store.IChainStore;
import io.fire.rule.spring.config.RuleEngineAutoConfiguration;
import io.fire.rule.spring.flowgram.execution.FlowGramExecutionService;
import io.fire.rule.spring.flowgram.node.*;
import io.fire.rule.spring.flowgram.runtime.FlowGramRuntimeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * FlowGram 集成（设计器试跑 + 运行时 Runtime API）的独立自动配置。
 *
 * <p>与引擎核心 {@link RuleEngineAutoConfiguration} 解耦：本包内的服务与 Web 端点
 * 是一个<b>可选集成</b>，可通过配置开关定向启停，后续接入其它集成（如别的设计器 / 编排台）
 * 也可各自建包并复制本类模式，互不干扰。
 *
 * <p><b>启停开关</b>：{@code rule.engine.flowgram.enabled}（默认 {@code true}，缺省即开启，
 * 与重构前行为一致）；置为 {@code false} 则下面的服务 Bean 与 {@code /flowgram/*}、{@code /api/*}
 * 端点全部不装配。生产级观测（{@code EngineObserver} 默认 Bean）不在此开关内，不受影响。
 *
 * <p><b>装配前提</b>：仅在 Servlet Web 环境生效，且需引擎核心已装配
 * （依赖 {@link ChainExecutor.Builder} Bean，故声明在 {@link RuleEngineAutoConfiguration} 之后）。
 *
 * @author Cai FeiYue
 * @date 2026-11-05
 */
@AutoConfiguration(after = RuleEngineAutoConfiguration.class)
@ConditionalOnProperty(prefix = "rule.engine.flowgram", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(ChainExecutor.Builder.class)
@Import({StartNode.class, LoopNode.class, ConditionNode.class, EndNode.class,
        MultiConditionNode.class, ScriptNode.class, VariableNode.class})
public class FlowGramAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IChainLoader.class)
    public IChainLoader chainLoader(INodeRegistry registry, INodeConfigBinder configBinder) {
        return new WorkflowSchemaLoader(registry, configBinder);
    }

    @Bean
    @ConditionalOnMissingBean(EngineObserver.class)
    public EngineObserver engineObserver(IChainStore chainStore, ObjectProvider<NodeProgressListener> listener) {
        return new TracingEngineObserver(chainStore, listener.getIfAvailable(() -> NodeProgressListener.NOOP));
    }

    @AutoConfiguration(after = RuleEngineAutoConfiguration.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    @ConditionalOnBean(ChainExecutor.Builder.class)
    @Import({FlowGramController.class, FlowGramRuntimeController.class})
    public static class FlowGramWebAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public WorkflowValidator workflowValidator(INodeRegistry registry, ObjectMapper objectMapper) {
            return new WorkflowValidator(registry, objectMapper);
        }

        @Bean
        @ConditionalOnMissingBean
        public FlowGramExecutionService flowGramExecutionService(
                ChainExecutor.Builder chainExecutorBuilder,
                ObjectProvider<RuleEngine.Builder> builderProvider) {
            return new FlowGramExecutionService(chainExecutorBuilder, builderProvider);
        }

        @Bean
        @ConditionalOnMissingBean
        public FlowGramRuntimeService flowGramRuntimeService(
                FlowGramExecutionService executionService,
                INodeRegistry registry,
                ObjectMapper objectMapper,
                IChainStore chainStore) {
            return new FlowGramRuntimeService(executionService, registry, objectMapper, chainStore);
        }

    }
}
