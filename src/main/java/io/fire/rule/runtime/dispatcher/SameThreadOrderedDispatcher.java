package io.fire.rule.runtime.dispatcher;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 懒创建通道分发器：同 key 串行，但通道按需创建、空闲可回收。
 *
 * <p><b>与 {@link KeyOrderedDispatcher} 的区别</b>：
 * <pre>
 *   KeyOrderedExecutor   固定 N 条通道，启动即建常驻线程
 *   本实现               按 key 动态建通道，空闲超时后销毁
 * </pre>
 *
 * <p><b>适用场景</b>：设备数极多但活跃 key 稀疏（百万设备、大部分时间静默）。
 * 固定开 64 条常驻线程在这种场景下是浪费。
 *
 * <p><b>代价</b>：并发创建通道有锁竞争，且 key 分布均匀时反而不如固定通道。
 * 先压测再决定——默认仍推荐 {@link KeyOrderedDispatcher}。
 */
public class SameThreadOrderedDispatcher implements TaskDispatcher {

    private final Executor workerPool;
    private final int maxChannels;
    private final long idleTimeoutMs;
    private final Map<String, Channel> channels = new HashMap<String, Channel>();
    private final Object lock = new Object();

    public SameThreadOrderedDispatcher(int maxChannels, long idleTimeoutMs, int poolSize) {
        this.maxChannels = maxChannels;
        this.idleTimeoutMs = idleTimeoutMs;
        this.workerPool = Executors.newFixedThreadPool(poolSize, new ThreadFactory() {
            private int i = 0;

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "dyn-dispatcher-" + (i++));
                t.setDaemon(true);
                return t;
            }
        });
    }

    @Override
    public void execute(String key, Runnable task) {
        Channel ch;
        synchronized (lock) {
            ch = channels.get(key);
            if (ch == null) {
                if (channels.size() >= maxChannels) {
                    // 通道数达上限：退化到共享池，放弃该 key 的串行保证
                    workerPool.execute(task);
                    return;
                }
                ch = new Channel();
                channels.put(key, ch);
            }
            ch.enqueue(task);
            if (!ch.running.get()) {
                ch.running.set(true);
                final Channel target = ch;
                final String k = key;
                workerPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        drain(k, target);
                    }
                });
            }
        }
    }

    private void drain(String key, Channel ch) {
        while (true) {
            Runnable task;
            synchronized (lock) {
                task = ch.queue.poll();
                if (task == null) {
                    ch.running.set(false);
                    // 空闲回收（简化：直接移除，生产可配 idleTimeoutMs 延迟清理）
                    if (ch.queue.isEmpty()) {
                        channels.remove(key);
                    }
                    return;
                }
            }
            try {
                task.run();
            } catch (RuntimeException e) {
                // 异常不外溢，由 PackContext 通过 callback 记账
            }
        }
    }

    @Override
    public void close() {
        if (workerPool instanceof ExecutorService) {
            ((ExecutorService) workerPool).shutdown();
        }
        synchronized (lock) {
            channels.clear();
        }
    }

    private static final class Channel {
        private final java.util.Queue<Runnable> queue = new ArrayDeque<Runnable>();
        private final AtomicBoolean running = new AtomicBoolean(false);

        void enqueue(Runnable r) {
            queue.offer(r);
        }
    }
}
