package io.fire.rule.cluster;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 哈希分区路由（默认实现）。
 *
 * <p><b>为什么用普通哈希而不是一致性哈希</b>：
 * 分区分配由消息队列的 rebalance 机制（Kafka consumer group）负责，
 * 分区总数固定，扩容时需人工介入或配合再平衡工具。
 * 这与 ThingsBoard 的做法一致——它用 Zookeeper 做分区分配，
 * 同样不是靠一致性哈希自动迁移。
 *
 * <p>扰动函数与本地 lane 保持一致（借鉴 ConcurrentHashMap），
 * 避免低位规律性导致的分区倾斜。
 */
public class HashPartitionRouter implements PartitionRouter {

    private final String nodeId;
    private final int partitionCount;
    private final AtomicReference<Set<Integer>> owned;

    public HashPartitionRouter(String nodeId, int partitionCount, Set<Integer> ownedPartitions) {
        this.nodeId = nodeId;
        this.partitionCount = partitionCount;
        this.owned = new AtomicReference<Set<Integer>>(
                ownedPartitions == null
                        ? Collections.<Integer>emptySet()
                        : new LinkedHashSet<Integer>(ownedPartitions));
    }

    /** 静态均分：用于测试或固定部署（如 3 实例 × 12 分区）。 */
    public static HashPartitionRouter staticAssign(String nodeId, int partitionCount,
                                                   int nodeIndex, int nodeTotal) {
        Set<Integer> mine = new LinkedHashSet<Integer>();
        for (int p = nodeIndex; p < partitionCount; p += nodeTotal) {
            mine.add(p);
        }
        return new HashPartitionRouter(nodeId, partitionCount, mine);
    }

    @Override
    public int partitionOf(String key, int partitionCount) {
        return spread(key == null ? 0 : key.hashCode()) % partitionCount;
    }

    public int partitionOf(String key) {
        return partitionOf(key, partitionCount);
    }

    @Override
    public Set<Integer> ownedPartitions() {
        return Collections.unmodifiableSet(owned.get());
    }

    /** 再平衡：由协调器调用，分区归属变化时更新。 */
    public void rebalance(Set<Integer> newPartitions) {
        owned.set(new LinkedHashSet<Integer>(newPartitions));
    }

    @Override
    public boolean isLocal(int partition) {
        return owned.get().contains(partition);
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public int partitionCount() {
        return partitionCount;
    }

    private static int spread(int h) {
        return (h ^ (h >>> 16)) & Integer.MAX_VALUE;
    }
}
