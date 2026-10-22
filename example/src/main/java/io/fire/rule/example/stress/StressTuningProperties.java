package io.fire.rule.example.stress;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 压测运行时可调参数。
 *
 * <p>只在 {@code example.stress.enabled=true} 时生效。取值 0 表示按 CPU 核数自动计算，
 * 便于同一份配置在不同规格机器上复用。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@ConfigurationProperties(prefix = "example.stress")
public class StressTuningProperties {

    /**
     * 并行通道数，0 = max(4, CPU 核数 * 2)。
     */
    private int laneCount;

    /**
     * 每个通道的排队容量，满后由提交线程自己执行形成背压。
     */
    private int laneQueueCapacity = 4096;

    /**
     * 异步节点线程数（AI 判定 / 异步写入），0 = max(4, CPU 核数 * 2)。
     */
    private int asyncPoolSize;

    /**
     * 异步节点排队容量。
     */
    private int asyncQueueCapacity = 4096;

    public int getLaneCount() {
        return laneCount;
    }

    public void setLaneCount(int laneCount) {
        this.laneCount = laneCount;
    }

    public int getLaneQueueCapacity() {
        return laneQueueCapacity;
    }

    public void setLaneQueueCapacity(int laneQueueCapacity) {
        this.laneQueueCapacity = laneQueueCapacity;
    }

    public int getAsyncPoolSize() {
        return asyncPoolSize;
    }

    public void setAsyncPoolSize(int asyncPoolSize) {
        this.asyncPoolSize = asyncPoolSize;
    }

    public int getAsyncQueueCapacity() {
        return asyncQueueCapacity;
    }

    public void setAsyncQueueCapacity(int asyncQueueCapacity) {
        this.asyncQueueCapacity = asyncQueueCapacity;
    }
}
