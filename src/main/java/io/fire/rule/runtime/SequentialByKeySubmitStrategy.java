package io.fire.rule.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

/**
 * 按 originator 串行：同一实体的下一条消息必须等上一条 ack。
 *
 * <p>这是默认推荐策略——设备状态更新、告警判定都依赖顺序。
 * 代价是热点实体会拖慢同批次，属于"用并行度换正确性"的取舍。
 *
 * <p>实现要点：{@link #onSuccess(UUID)} 是解锁信号。
 * 收到某条消息完成后，立即放行该实体的下一条，
 * 无需等整批结束，因此吞吐损失远小于"全局串行"。
 */
public class SequentialByKeySubmitStrategy implements SubmitStrategy {

    private final Map<UUID, Envelope> pending = new LinkedHashMap<UUID, Envelope>();
    private final Map<String, Queue<Envelope>> queuesByKey = new HashMap<String, Queue<Envelope>>();
    private final Map<String, Envelope> inFlight = new HashMap<String, Envelope>();
    private final List<String> keys = new ArrayList<String>();
    private MessageConsumer consumer;

    @Override
    public void init(List<Envelope> msgs) {
        reset();
        for (Envelope e : msgs) {
            enqueue(e);
        }
    }

    @Override
    public Map<UUID, Envelope> pendingMap() {
        return pending;
    }

    @Override
    public synchronized void submitAttempt(MessageConsumer consumer) {
        this.consumer = consumer;
        for (String key : keys) {
            submitNextForKey(key);
        }
    }

    @Override
    public synchronized void onSuccess(UUID id) {
        release(id);
    }

    @Override
    public synchronized void onFailure(UUID id) {
        // 失败同样要放行，否则该实体的后续消息会一直卡到批次超时
        release(id);
    }

    private void release(UUID id) {
        String key = null;
        for (Map.Entry<String, Envelope> entry : inFlight.entrySet()) {
            if (entry.getValue().getId().equals(id)) {
                key = entry.getKey();
                break;
            }
        }
        if (key != null) {
            inFlight.remove(key);
            submitNextForKey(key); // 放行同实体的下一条
        }
    }

    private void submitNextForKey(String key) {
        if (consumer == null || inFlight.containsKey(key)) {
            return;
        }
        Queue<Envelope> q = queuesByKey.get(key);
        if (q == null || q.isEmpty()) {
            return;
        }
        Envelope next = q.poll();
        inFlight.put(key, next);
        consumer.accept(next.getId(), next);
    }

    @Override
    public void update(Map<UUID, Envelope> reprocessMap) {
        reset();
        for (Envelope e : reprocessMap.values()) {
            enqueue(e);
        }
    }

    @Override
    public void stop() {
        reset();
        consumer = null;
    }

    private void enqueue(Envelope e) {
        pending.put(e.getId(), e);
        String key = e.getMessage().getOriginator();
        Queue<Envelope> q = queuesByKey.get(key);
        if (q == null) {
            q = new LinkedList<Envelope>();
            queuesByKey.put(key, q);
            keys.add(key);
        }
        q.add(e);
    }

    private void reset() {
        pending.clear();
        queuesByKey.clear();
        inFlight.clear();
        keys.clear();
    }
}
