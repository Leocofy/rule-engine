package io.fire.rule.cluster;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.runtime.Envelope;
import io.fire.rule.runtime.MessageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 分区消息源：消费本实例负责的分区，并把字节流还原成引擎消息。
 *
 * <p>这是分布式链路的"入口侧"：
 * <pre>
 *   broker.poll(partition) → 反序列化 → Envelope(含 relations) → PackProcessor
 * </pre>
 *
 * <p>与 {@link DistributedIMessageSink} 对称，两者构成跨实例闭环。
 */
public class PartitionedMessageSource implements MessageSource {

    private final String name;
    private final PartitionRouter router;
    private final MessageSerializer serializer;
    private final InMemoryClusterBroker broker;
    private final int pollSize;

    public PartitionedMessageSource(String name, PartitionRouter router,
                                    MessageSerializer serializer,
                                    InMemoryClusterBroker broker, int pollSize) {
        this.name = name;
        this.router = router;
        this.serializer = serializer;
        this.broker = broker;
        this.pollSize = pollSize;
    }

    @Override
    public List<Envelope> poll(int maxCount) {
        List<Envelope> out = new ArrayList<>();
        int per = Math.min(maxCount, pollSize);
        for (Integer p : router.ownedPartitions()) {
            for (byte[] data : broker.poll(p, per)) {
                TransportMessage t = serializer.deserialize(data);
                RuleMessage msg = serializer.toRuleMessage(t);
                Set<String> rels = t.getRelations();
                out.add(new Envelope(UUID.randomUUID().toString(), msg, null,
                        rels.isEmpty() ? null : rels));
            }
            if (out.size() >= maxCount) {
                break;
            }
        }
        return out;
    }

    @Override
    public void commit() {
        // 内存 broker 在 poll 时即出队，无需额外提交
    }

    @Override
    public String name() {
        return name;
    }
}
