package io.fire.rule;

import io.fire.rule.chain.ChainExecutor;
import io.fire.rule.chain.RuleChain;
import io.fire.rule.cluster.DistributedIMessageSink;
import io.fire.rule.cluster.JsonMessageSerializer;
import io.fire.rule.cluster.MessageSerializer;
import io.fire.rule.cluster.PartitionRouter;
import io.fire.rule.config.IChainLoader;
import io.fire.rule.config.JsonChainLoader;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.observability.EngineObserver;
import io.fire.rule.runtime.*;
import io.fire.rule.runtime.dispatcher.KeyOrderedDispatcher;
import io.fire.rule.runtime.dispatcher.TaskDispatcher;
import io.fire.rule.runtime.strategy.BurstSubmitStrategy;
import io.fire.rule.runtime.strategy.SubmitStrategy;
import io.fire.rule.spi.*;
import io.fire.rule.store.IChainStore;
import io.fire.rule.store.InMemoryChainStore;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 规则引擎门面：把所有层组装起来，对外只暴露最小必要接口。
 *
 * <p>典型用法：
 * <pre>
 * RuleEngine engine = RuleEngine.builder()
 *         .expressionEngine(new SimpleExpressionEngine())
 *         .registry(registry)
 *         .source(new InMemoryMessageSource("main"))
 *         .build();
 * engine.loadChain(chainJson);
 * engine.start();          // 启动消费循环
 * engine.submit(message);  // 投递消息
 * </pre>
 *
 * <p>分层回顾：
 * <pre>
 *   模型层  model      不可变消息 / 元数据 / 处理栈
 *   契约层  spi        节点 SPI / 上下文 / 注册表 / 表达式
 *   编排层  chain      DAG 定义与执行器
 *   运行层  runtime    分区串行 / 批次 / 提交与重试策略
 *   治理层  config     JSON 定义与加载
 *   观测层  observability 统计与追踪
 * </pre>
 */
public class RuleEngine {

    private final INodeRegistry registry;
    private final ChainExecutor chainExecutor;
    private final PackProcessor processor;
    private final ExecutorService asyncPool;
    private final ExecutorService consumerExecutor;
    private final EngineObserver observer;

    private RuleEngine(Builder b) {
        this.registry = b.registry;
        this.observer = b.observer != null ? b.observer : EngineObserver.NOOP;
        this.asyncPool = b.asyncPool;

        if (b.defaultChainId == null) {
            throw new TaskExecuteException(RuleEngine.class, "defaultChainId 未设置");
        }

        IChainLoader loader = b.loader != null ? b.loader : new JsonChainLoader(b.registry);
        this.chainExecutor = ChainExecutor.builder()
                .expressionEngine(b.expressionEngine)
                .asyncExecutor(asyncPool)
                .chainStore(b.chainStore != null ? b.chainStore : new InMemoryChainStore(loader))
                .sink(b.sink)
                .observer(observer)
                .capabilities(b.capabilities == null ? null : b.capabilities.map)
                .build();

        this.processor = PackProcessor.builder()
                .source(b.source)
                .chainExecutor(chainExecutor)
                .submitStrategy(b.submitStrategy)
                .retryStrategy(b.retryStrategy)
                .dispatcher(b.dispatcher)
                .observer(observer)
                .pollSize(b.pollSize)
                .pollIntervalMs(b.pollIntervalMs)
                .packTimeoutMs(b.packTimeoutMs)
                .build();

        // 分布式装配：配置了分区路由器时，自动构造投递器并注入（解决循环依赖）
        if (b.partitionRouter != null && b.remoteTransport != null) {
            MessageSerializer ser = b.messageSerializer != null ? b.messageSerializer : new JsonMessageSerializer();
            DistributedIMessageSink distSink =
                    new DistributedIMessageSink(
                            b.partitionRouter, ser, b.remoteTransport, chainExecutor);
            chainExecutor.setMessageSink(distSink);
        }

        this.consumerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rule-consumer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 从 JSON 加载规则链。
     */
    public RuleChain loadChain(String json) throws NodeException {
       return chainExecutor.register(json);
    }

    /**
     * 启动消费循环（非阻塞）。
     */
    public void start() {
        consumerExecutor.submit(processor::run);
    }

    public void stop() {
        processor.stopAndAwait(10000L);
        consumerExecutor.shutdown();
        chainExecutor.shutdown();
        asyncPool.shutdown();
    }

    public INodeRegistry registry() {
        return registry;
    }

    public ChainExecutor chainExecutor() {
        return chainExecutor;
    }

    public EngineObserver observer() {
        return observer;
    }

    /**
     * 同步处理一条消息，绕过队列，便于测试与单条调试。
     */
    public void submitSync(RuleMessage msg) {
        processor.processPack(Collections.singletonList(new Envelope(msg)));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private INodeRegistry registry = NodeRegistry.defaultRegistry();
        private IExpressionEngine expressionEngine;
        private MessageSource source;
        private IMessageSink sink;
        private IChainLoader loader;
        private IChainStore chainStore;
        private EngineObserver observer = EngineObserver.NOOP;
        private SubmitStrategy submitStrategy = new BurstSubmitStrategy();
        private RetryStrategy retryStrategy = RetryStrategy.retryFailed();
        private TaskDispatcher dispatcher = KeyOrderedDispatcher.defaultDispatcher();
        private ExecutorService asyncPool = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
        private Capabilities capabilities;
        private int pollSize = 256;
        private final long pollIntervalMs = 25L;
        private long packTimeoutMs = 2000L;
        private String defaultChainId;
        private PartitionRouter partitionRouter;
        private MessageSerializer messageSerializer;
        private DistributedIMessageSink.RemoteTransport remoteTransport;

        /**
         * 消息未携带 chainId 时使用的默认链。
         * 注册了多条链时强烈建议显式设置，否则消息会因找不到目标链而失败。
         */
        public Builder defaultChainId(String defaultChainId) {
            this.defaultChainId = defaultChainId;
            return this;
        }

        public Builder registry(INodeRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder expressionEngine(IExpressionEngine expressionEngine) {
            this.expressionEngine = expressionEngine;
            return this;
        }

        public Builder source(MessageSource source) {
            this.source = source;
            return this;
        }

        public Builder sink(IMessageSink sink) {
            this.sink = sink;
            return this;
        }

        public Builder observer(EngineObserver observer) {
            this.observer = observer;
            return this;
        }

        public Builder loader(IChainLoader loader) {
            this.loader = loader;
            return this;
        }

        public Builder chainStore(IChainStore store) {
            this.chainStore = store;
            return this;
        }

        /**
         * 默认 Burst；需要保序请传 SequentialByKeySubmitStrategy。
         */
        public Builder submitStrategy(SubmitStrategy submitStrategy) {
            this.submitStrategy = submitStrategy;
            return this;
        }

        public Builder retryStrategy(RetryStrategy retryStrategy) {
            this.retryStrategy = retryStrategy;
            return this;
        }

        public Builder dispatcher(TaskDispatcher dispatcher) {
            this.dispatcher = dispatcher;
            return this;
        }

        public Builder asyncPool(ExecutorService asyncPool) {
            this.asyncPool = asyncPool;
            return this;
        }

        public <T> Builder capability(Class<T> type, T impl) {
            if (capabilities == null) {
                capabilities = new Capabilities();
            }
            capabilities.map.put(type, impl);
            return this;
        }

        public Builder pollSize(int pollSize) {
            this.pollSize = pollSize;
            return this;
        }

        public Builder packTimeoutMs(long packTimeoutMs) {
            this.packTimeoutMs = packTimeoutMs;
            return this;
        }

        /**
         * 启用分布式：提供分区路由器（决定消息归哪个实例）。
         */
        public Builder partitionRouter(io.fire.rule.cluster.PartitionRouter router) {
            this.partitionRouter = router;
            return this;
        }

        /**
         * 跨节点序列化器，默认 JSON。
         */
        public Builder messageSerializer(io.fire.rule.cluster.MessageSerializer serializer) {
            this.messageSerializer = serializer;
            return this;
        }

        public io.fire.rule.cluster.PartitionRouter partitionRouter() {
            return partitionRouter;
        }

        /**
         * 跨实例传输通道（Kafka / RabbitMQ / 内存 broker）。
         */
        public Builder remoteTransport(DistributedIMessageSink.RemoteTransport t) {
            this.remoteTransport = t;
            return this;
        }

        public RuleEngine build() {
            if (expressionEngine == null) {
                throw new IllegalStateException("ExpressionEngine 必填");
            }
            if (source == null) {
                throw new IllegalStateException("MessageSource 必填");
            }
            return new RuleEngine(this);
        }
    }

    /**
     * 能力容器，用于向节点注入业务服务。
     */
    public static final class Capabilities {
        private final Map<Class<?>, Object> map = new HashMap<>();
    }
}
