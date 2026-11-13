package io.fire.rule.core.runtime.strategy;

import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.runtime.Envelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 回归：验证 {@link SequentialByKeySubmitStrategy} 的串行语义，
 * 以及 update / stop 与 onSuccess / onFailure 并发时不会抛
 * ConcurrentModificationException 或丢失消息。
 */
public class SequentialByKeySubmitStrategyTest {

    private static Envelope envelope(String id, String originator) {
        return new Envelope(id, RuleMessage.newMessage().originator(originator).build(), null);
    }

    private static Map<String, Envelope> asMap(Envelope... envelopes) {
        Map<String, Envelope> map = new LinkedHashMap<>();
        for (Envelope e : envelopes) {
            map.put(e.getId(), e);
        }
        return map;
    }

    // Test scenario: 同一 originator 的消息必须等上一条 ack 后才放行下一条。
    @Test
    public void sameKeyMessagesAreReleasedSerially() {
        SequentialByKeySubmitStrategy strategy = new SequentialByKeySubmitStrategy();
        strategy.init(Arrays.asList(envelope("a1", "o1"), envelope("a2", "o1"), envelope("b1", "o2")));

        List<String> delivered = new ArrayList<>();
        strategy.submitAttempt((id, msg) -> delivered.add(id));

        assertEquals(Arrays.asList("a1", "b1"), delivered, "每个 key 只应先投递首条消息");

        strategy.onSuccess("a1");
        assertEquals(Arrays.asList("a1", "b1", "a2"), delivered,
                "成功后应立即放行同一 key 的下一条");

        strategy.onFailure("a2");
        assertEquals(Arrays.asList("a1", "b1", "a2"), delivered,
                "队列为空时失败回调不应产生额外投递");
    }

    // Test scenario: 多线程并发回调 onSuccess 时不得抛异常，也不得重复投递或丢投递。
    @Test
    public void concurrentCallbacksReleaseWithoutLossOrException() throws Exception {
        int count = 200;
        List<Envelope> envelopes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            envelopes.add(envelope("k" + i, "o" + i));
        }
        SequentialByKeySubmitStrategy strategy = new SequentialByKeySubmitStrategy();
        strategy.init(envelopes);

        final List<String> delivered = Collections.synchronizedList(new ArrayList<String>());
        strategy.submitAttempt((id, msg) -> delivered.add(id));
        assertEquals(count, delivered.size(), "每个 key 的首条消息都应投递");

        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(count);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < count; i++) {
                final String id = "k" + i;
                pool.execute(() -> {
                    try {
                        start.await();
                        strategy.onSuccess(id);
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "并发回调应在超时前完成");
        } finally {
            pool.shutdownNow();
        }

        assertNull(error.get(), "并发 onSuccess 不应抛异常");
        assertEquals(count, delivered.size(), "队列为空时并发解锁不应产生重复投递");
    }

    // Test scenario: update / stop 与 onSuccess / onFailure 并发交错时不得抛 CME，且之后仍能完整投递。
    @Test
    public void concurrentUpdateStopAndCallbacksKeepFinalStateConsistent() throws Exception {
        SequentialByKeySubmitStrategy strategy = new SequentialByKeySubmitStrategy();
        List<Envelope> initial = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            initial.add(envelope("i" + i, "o" + i));
        }
        strategy.init(initial);
        strategy.submitAttempt((id, msg) -> {
        });

        final Map<String, Envelope> churn =
                asMap(envelope("c1", "c"), envelope("c2", "c"), envelope("c3", "d"));
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(8);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int t = 0; t < 8; t++) {
                final int mode = t % 4;
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 500; i++) {
                            switch (mode) {
                                case 0:
                                    strategy.update(churn);
                                    break;
                                case 1:
                                    strategy.onSuccess("c1");
                                    break;
                                case 2:
                                    strategy.onFailure("c2");
                                    break;
                                default:
                                    strategy.stop();
                                    break;
                            }
                        }
                    } catch (Throwable t1) {
                        error.compareAndSet(null, t1);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS), "并发压力阶段应在超时前结束");
        } finally {
            pool.shutdownNow();
        }

        assertNull(error.get(),
                "update/stop 与 onSuccess/onFailure 并发不应抛 ConcurrentModificationException 等异常");

        Map<String, Envelope> finalBatch =
                asMap(envelope("f1", "x"), envelope("f2", "y"), envelope("f3", "z"));
        strategy.update(finalBatch);
        List<String> delivered = new ArrayList<>();
        strategy.submitAttempt((id, msg) -> delivered.add(id));

        assertEquals(new HashSet<>(Arrays.asList("f1", "f2", "f3")), new HashSet<>(delivered),
                "并发结束后策略仍应完整保存并投递全部消息");
        assertEquals(3, delivered.size(), "不同 key 的消息不应丢失");
    }
}