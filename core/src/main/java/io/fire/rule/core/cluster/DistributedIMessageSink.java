package io.fire.rule.core.cluster;

import io.fire.rule.core.chain.ChainExecutor;
import io.fire.rule.core.chain.ExecutionContext;
import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.spi.IMessageSink;

import java.util.Set;

/**
 * 分布式消息投递器：本地优先，远程落队列。
 *
 * <p><b>核心优化</b>：目标分区就在本实例时，直接内存 dispatch，
 * 完全不走序列化与网络。只有跨实例时才序列化投递。
 *
 * <p>这带来一个关键性质：<b>大多数消息不会产生网络开销</b>。
 * 因为分区键是 originator，同一设备的消息恒定落在同一分区，
 * 而该分区由固定实例消费——所以"设备 → 实例"的映射是稳定的，
 * 链路后续各跳大概率都还在同一实例内。
 *
 * <p>这也是 ThingsBoard 同时提供 tellNext 与 enqueueForTellNext 的原因。
 */
public class DistributedIMessageSink implements IMessageSink {

    private final PartitionRouter router;
    private final MessageSerializer serializer;
    private final RemoteTransport transport;
    private final ChainExecutor chainExecutor;

    public DistributedIMessageSink(PartitionRouter router,
                                   MessageSerializer serializer,
                                   RemoteTransport transport,
                                   ChainExecutor chainExecutor) {
        this.router = router;
        this.serializer = serializer;
        this.transport = transport;
        this.chainExecutor = chainExecutor;
    }

    @Override
    public boolean isLocal(String chainId, RuleMessage msg) {
        return router.isLocal(partitionOf(msg));
    }

    @Override
    public void send(String chainId, String nodeId, RuleMessage msg, Set<String> relations) {
        String key = msg.getOriginator() == null ? "default" : msg.getOriginator();
        int partition = partitionOf(msg);

        if (router.isLocal(partition)) {
            // 直接调用 send 时没有批次回调，仅作为本地路由兜底；
            // 节点侧 enqueueNext 会先查 isLocal 并改走 next，避免批次挂起。
            chainExecutor.resume(msg, chainId, nodeId, relations, new ExecutionContext());
            return;
        }
        // 远程：序列化后投递到目标分区
        byte[] data = serializer.serialize(msg, chainId, nodeId, relations);
        transport.send(chainId, partition, key, data);
    }

    private int partitionOf(RuleMessage msg) {
        String key = msg.getOriginator() == null ? "default" : msg.getOriginator();
        return router.partitionOf(key, router.partitionCount());
    }

    /**
     * 远程传输抽象：接 Kafka / RabbitMQ / Pulsar。
     * 内置可先用内存实现做多实例模拟，生产替换为真实 broker。
     */
    public interface RemoteTransport {
        void send(String queueName, int partition, String key, byte[] data);
    }
}
