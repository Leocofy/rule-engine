package io.fire.rule.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 内存版集群 broker：用于多实例模拟与单元测试。
 *
 * <p>模拟 Kafka 的分区语义：
 * <ul>
 *   <li>按分区存储，保证分区内 FIFO</li>
 *   <li>poll(partition) 只取该分区的消息</li>
 * </ul>
 * 生产环境替换为 Kafka 实现即可，引擎代码无需改动。
 */
public class InMemoryClusterBroker implements DistributedIMessageSink.RemoteTransport {

    private final Map<Integer, ConcurrentLinkedQueue<byte[]>> partitions =
            new ConcurrentHashMap<Integer, ConcurrentLinkedQueue<byte[]>>();
    private final int partitionCount;

    /** 统计跨实例投递次数，用于验证本地优先是否生效。 */
    private volatile long remoteSendCount;

    public InMemoryClusterBroker(int partitionCount) {
        this.partitionCount = partitionCount;
        for (int i = 0; i < partitionCount; i++) {
            partitions.put(i, new ConcurrentLinkedQueue<byte[]>());
        }
    }

    @Override
    public void send(String queueName, int partition, String key, byte[] data) {
        ConcurrentLinkedQueue<byte[]> q = partitions.get(partition);
        if (q == null) {
            throw new IllegalArgumentException("分区不存在: " + partition);
        }
        q.offer(data);
        remoteSendCount++;
    }

    /** 拉取指定分区的一批消息（模拟 consumer.poll）。 */
    public List<byte[]> poll(int partition, int maxCount) {
        ConcurrentLinkedQueue<byte[]> q = partitions.get(partition);
        if (q == null) {
            return Collections.emptyList();
        }
        List<byte[]> batch = new ArrayList<byte[]>(maxCount);
        for (int i = 0; i < maxCount; i++) {
            byte[] data = q.poll();
            if (data == null) {
                break;
            }
            batch.add(data);
        }
        return batch;
    }

    public int pendingOf(int partition) {
        ConcurrentLinkedQueue<byte[]> q = partitions.get(partition);
        return q == null ? 0 : q.size();
    }

    public int totalPending() {
        int n = 0;
        for (ConcurrentLinkedQueue<byte[]> q : partitions.values()) {
            n += q.size();
        }
        return n;
    }

    public int partitionCount() {
        return partitionCount;
    }

    public long remoteSendCount() {
        return remoteSendCount;
    }
}
