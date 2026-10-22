package io.fire.rule.example.stress;

import io.fire.rule.example.service.MockSinkHandler;
import io.fire.rule.example.source.CallBackSource;
import io.fire.rule.runtime.dispatcher.KeyOrderedDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 压测观测端点。
 *
 * <p>{@code GET /stress/stats} 汇总入口积压、引擎吞吐、通道队列、异步池与 JVM 状态；
 * {@code POST /stress/reset} 清零速率统计与投递记录，便于分段压测。
 *
 * <p>仅在 {@code example.stress.enabled=true} 时注册，生产环境不会暴露。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@RestController
@RequestMapping("/stress")
@ConditionalOnProperty(name = "example.stress.enabled", havingValue = "true")
public class StressMetricsController {

    private final StressEngineObserver observer;
    private final CallBackSource callBackSource;
    private final KeyOrderedDispatcher dispatcher;
    private final ThreadPoolExecutor asyncPool;
    private final MockSinkHandler sinkHandler;

    public StressMetricsController(StressEngineObserver observer,
                                   CallBackSource callBackSource,
                                   KeyOrderedDispatcher dispatcher,
                                   ThreadPoolExecutor asyncPool,
                                   MockSinkHandler sinkHandler) {
        this.observer = observer;
        this.callBackSource = callBackSource;
        this.dispatcher = dispatcher;
        this.asyncPool = asyncPool;
        this.sinkHandler = sinkHandler;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", OffsetDateTime.now().toString());
        result.put("ingress", ingress());
        result.put("engine", observer.snapshot());
        result.put("asyncPool", asyncPool());
        result.put("lanes", lanes());
        result.put("jvm", jvm());
        return result;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        observer.reset();
        sinkHandler.reset();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reset", true);
        result.put("timestamp", OffsetDateTime.now().toString());
        return result;
    }

    private Map<String, Object> ingress() {
        Map<String, Object> ingress = new LinkedHashMap<>();
        ingress.put("callbackQueue", callBackSource.size());
        ingress.put("pendingBatches", callBackSource.pendingBatches());
        return ingress;
    }

    private Map<String, Object> asyncPool() {
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("size", asyncPool.getPoolSize());
        pool.put("active", asyncPool.getActiveCount());
        pool.put("queue", asyncPool.getQueue().size());
        pool.put("completed", asyncPool.getCompletedTaskCount());
        pool.put("largest", asyncPool.getLargestPoolSize());
        return pool;
    }

    private Map<String, Object> lanes() {
        int[] queueSizes = dispatcher.queueSizes();
        int totalQueued = 0;
        for (int size : queueSizes) {
            totalQueued += size;
        }
        Map<String, Object> lanes = new LinkedHashMap<>();
        lanes.put("count", dispatcher.laneCount());
        lanes.put("queueSizes", queueSizes);
        lanes.put("totalQueued", totalQueued);
        return lanes;
    }

    private Map<String, Object> jvm() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("heapUsedMb", mb(runtime.totalMemory() - runtime.freeMemory()));
        jvm.put("heapTotalMb", mb(runtime.totalMemory()));
        jvm.put("heapMaxMb", mb(runtime.maxMemory()));
        jvm.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        jvm.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        return jvm;
    }

    private double mb(long bytes) {
        return Math.round(bytes / 1024d / 1024d * 10d) / 10d;
    }
}
