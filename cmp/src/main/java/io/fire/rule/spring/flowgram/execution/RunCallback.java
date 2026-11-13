package io.fire.rule.spring.flowgram.execution;

import io.fire.rule.core.chain.ExecutionCallback;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 单次试跑的完成回调：fan-out 时按分支计数，全部回来才算完成。 */
final class RunCallback implements ExecutionCallback {

    private final AtomicInteger pending = new AtomicInteger(1);
    private final CountDownLatch latch = new CountDownLatch(1);

    private volatile Throwable error;
    private volatile boolean expired;

    @Override
    public void onComplete() {
        if (pending.decrementAndGet() <= 0) {
            latch.countDown();
        }
    }

    @Override
    public void onError(Throwable t) {
        error = t;
        latch.countDown();
    }

    @Override
    public void onFanOut(int branches) {
        if (branches > 1) {
            pending.addAndGet(branches - 1);
        }
    }

    @Override
    public boolean isValid() {
        return !expired;
    }

    boolean await(long timeoutMs) {
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            expired = true;
        }
    }

    Throwable error() {
        return error;
    }
}