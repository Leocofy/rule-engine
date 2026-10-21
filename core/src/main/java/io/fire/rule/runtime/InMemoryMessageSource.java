package io.fire.rule.runtime;

import io.fire.rule.model.RuleMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 内存消息源：用于测试与单机部署。
 * 生产环境替换为 Kafka / RabbitMQ 实现即可，引擎无需改动。
 */
public class InMemoryMessageSource implements MessageSource {

    private final String name;
    private final BlockingQueue<Envelope> queue = new LinkedBlockingQueue<Envelope>();
    private final List<Envelope> pendingAck = new ArrayList<Envelope>();

    public InMemoryMessageSource(String name) {
        this.name = name;
    }

    public void offer(RuleMessage msg) {
        queue.offer(new Envelope(msg));
    }

    @Override
    public List<Envelope> poll(int maxCount) {
        List<Envelope> batch = new ArrayList<Envelope>(maxCount);
        queue.drainTo(batch, maxCount);
        if (batch.isEmpty()) {
            try {
                Envelope first = queue.poll(50, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, maxCount - 1);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        synchronized (pendingAck) {
            pendingAck.addAll(batch);
        }
        return batch;
    }

    @Override
    public void commit() {
        synchronized (pendingAck) {
            pendingAck.clear();
        }
    }

    @Override
    public String name() {
        return name;
    }

    public int size() {
        return queue.size();
    }
}
