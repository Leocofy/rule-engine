package io.fire.rule.cluster;

import java.util.Set;

/**
 * 分区路由：决定一条消息该进哪个分区、由哪个实例处理。
 *
 * <p><b>这是分布式成立的前提</b>：同一 originator 必须恒定映射到同一分区，
 * 否则"同一设备消息有序"的保证会断。
 *
 * <p>两层分区协同：
 * <pre>
 *   Kafka 分区（跨实例） → 本实例的 lane（跨线程）
 * </pre>
 * 两层必须用同一个键（originator），顺序保证才能贯通。
 */
public interface PartitionRouter {

    /** 按分区键计算目标分区。 */
    int partitionOf(String key, int partitionCount);

    /** 当前实例负责的分区集合；由协调器在再平衡后更新。 */
    Set<Integer> ownedPartitions();

    /** 该分区是否由当前实例负责。 */
    boolean isLocal(int partition);

    /** 实例标识，用于日志与调试。 */
    String nodeId();

    /** 分区总数。 */
    int partitionCount();
}
