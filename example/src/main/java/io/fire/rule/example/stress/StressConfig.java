package io.fire.rule.example.stress;

import io.fire.rule.runtime.dispatcher.KeyOrderedDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 压测专用运行时装配。
 *
 * <p>把引擎内部默认的通道数与线程池替换为可配置实现，便于通过压测找到拐点。
 * 两个 Bean 都声明 {@code destroyMethod=""}，生命周期统一交给
 * {@link io.fire.rule.RuleEngine#stop()} 管理，避免重复关闭。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@Configuration
@ConditionalOnProperty(name = "example.stress.enabled", havingValue = "true")
@EnableConfigurationProperties(StressTuningProperties.class)
public class StressConfig {

    @Bean(destroyMethod = "")
    public KeyOrderedDispatcher stressDispatcher(StressTuningProperties props) {
        int lanes = props.getLaneCount() > 0
                ? props.getLaneCount()
                : Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        return new KeyOrderedDispatcher(lanes, Math.max(1, props.getLaneQueueCapacity()));
    }

    @Bean(destroyMethod = "")
    public ThreadPoolExecutor stressAsyncPool(StressTuningProperties props) {
        int threads = props.getAsyncPoolSize() > 0
                ? props.getAsyncPoolSize()
                : Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "stress-async-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, props.getAsyncQueueCapacity())),
                factory, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 压测观察者：在默认节点统计之外补充批次数、超时、死信与实时 TPS。
     *
     * <p>该 Bean 会让 starter 的默认 {@code EngineObserver.Stats} 自动退让。
     */
    @Bean
    public StressEngineObserver stressEngineObserver() {
        return new StressEngineObserver();
    }
}
