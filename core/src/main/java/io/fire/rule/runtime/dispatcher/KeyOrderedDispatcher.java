package io.fire.rule.runtime.dispatcher;

import java.util.concurrent.*;

/**
 * 按键分区串行执行器。
 *
 * <p><b>解决的问题</b>：同一设备（originator）的消息必须顺序处理，
 * 不同设备要能并行。传统做法是加锁，这里用"固定通道 + 哈希路由"消除锁。
 *
 * <p><b>为什么不直接用 Actor 框架</b>：
 * 我们要的只是"状态私有 + 串行执行"，用 Akka/Pekko 会引入监督树、
 * 远程通信、调试困难等一大堆用不上的复杂度。这个实现不到一百行，
 * 出问题时堆栈完整、能看懂能改。
 *
 * <p><b>背压设计（关键）</b>：队列必须有界。用 CallerRunsPolicy，
 * 队列满时让提交方自己执行，形成自然的反压而不是 OOM。
 */
public class KeyOrderedDispatcher implements TaskDispatcher {

    private final ExecutorService[] lanes;
    private final int laneCount;

    public KeyOrderedDispatcher(int laneCount, int queueCapacity) {
        this.laneCount = laneCount;
        this.lanes = new ExecutorService[laneCount];
        for (int i = 0; i < laneCount; i++) {
            final int laneIndex = i;
            lanes[i] = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    r -> {
                        Thread t = new Thread(r, "rule-lane-" + laneIndex);
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
        }
    }


    /**
     * 默认实例：通道数取 CPU 核数×2，队列容量 1024。
     */
    public static KeyOrderedDispatcher defaultDispatcher() {
        return new KeyOrderedDispatcher(defaultLaneCount(), 1024);
    }

    /**
     * 同一 key 的任务严格串行；不同 key 可并行。
     */
    public void execute(String key, Runnable task) {
        lanes[laneIndex(key)].execute(task);
    }

    public <T> Future<T> submit(String key, Callable<T> task) {
        return lanes[laneIndex(key)].submit(task);
    }

    /**
     * 同一 key 的批量任务一次入队，减少哈希与调度开销。
     */
    public void executeBatch(String key, final Iterable<Runnable> tasks) {
        lanes[laneIndex(key)].execute(() -> {
            for (Runnable t : tasks) {
                t.run();
            }
        });
    }

    public int laneIndex(String key) {
        return spread(key == null ? 0 : key.hashCode()) % laneCount;
    }

    public int laneCount() {
        return laneCount;
    }

    /**
     * 借鉴 ConcurrentHashMap 的扰动函数，避免低位规律性导致的倾斜。
     */
    private static int spread(int h) {
        return (h ^ (h >>> 16)) & Integer.MAX_VALUE;
    }

    /**
     * 建议通道数：CPU 核数的 2~4 倍，最终按压测调整。
     */
    private static int defaultLaneCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(4, cores * 2);
    }

    @Override
    public void close() {
        for (ExecutorService lane : lanes) {
            lane.shutdown();
        }
    }
}
