package io.fire.rule.runtime.strategy;

import io.fire.rule.runtime.Envelope;

import java.util.*;

/**
 * 整批按序立即提交：吞吐最高，不保证同实体顺序。
 * 适合无顺序要求的遥测写入。
 */
public class BurstSubmitStrategy implements SubmitStrategy {

    private final Map<String, Envelope> pending = new LinkedHashMap<>();
    private List<Envelope> ordered;

    public BurstSubmitStrategy() {
    }

    @Override
    public void init(List<Envelope> msgs) {
        // 必须拷贝：传入的可能是 Collections.singletonList 等不可变列表，
        // 直接持有并在 submitAttempt 里 clear() 会抛 UnsupportedOperationException
        this.ordered = new ArrayList<>(msgs);
        pending.clear();
        for (Envelope e : msgs) {
            pending.put(e.getId(), e);
        }
    }

    @Override
    public Map<String, Envelope> pendingMap() {
        return pending;
    }

    @Override
    public void submitAttempt(MessageConsumer consumer) {
        for (Envelope e : ordered) {
            consumer.accept(e.getId(), e);
        }
        ordered.clear();   // 已全量投递，避免重试时重复
    }

    @Override
    public void update(Map<String, Envelope> reprocessMap) {
        pending.clear();
        pending.putAll(reprocessMap);
        this.ordered = new java.util.ArrayList<>(reprocessMap.values());
    }

    @Override
    public void onSuccess(String id) {
        // 整批已投递，无需解锁
    }

    @Override
    public void onFailure(String id) {
        // 整批已投递，无需解锁
    }

    @Override
    public void stop() {
        pending.clear();
    }
}
