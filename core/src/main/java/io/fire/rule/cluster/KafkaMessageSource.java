package io.fire.rule.cluster;

import io.fire.rule.runtime.Envelope;
import io.fire.rule.runtime.MessageSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Kafka 消息源示例实现。
 *
 * <p><b>依赖</b>：org.apache.kafka:kafka-clients（需自行引入，核心保持零依赖）。
 *
 * <p>三个关键点：
 * <ol>
 *   <li><b>分区键必须用 originator</b> —— 这是"同一设备消息有序"的根因，
 *       换成别的键会直接破坏顺序保证</li>
 *   <li><b>手动提交</b> —— 必须等整批处理成功再 commit，
 *       用自动提交会在处理失败时丢消息</li>
 *   <li><b>消费者数量 ≤ 分区数</b> —— 多余的消费者会空转</li>
 * </ol>
 *
 * <pre>
 * // 生产者侧（对应 DistributedMessageSink.RemoteTransport）
 * producer.send(new ProducerRecord<>(topic, partition, originator, data));
 * </pre>
 */
public class KafkaMessageSource implements MessageSource {

    private final String name;
    private final MessageSerializer serializer;

    public KafkaMessageSource(String name, MessageSerializer serializer) {
        this.name = name;
        this.serializer = serializer;
    }

    @Override
    public List<Envelope> poll(int maxCount) {
        List<Envelope> out = new ArrayList<>();
        // ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
        // for (ConsumerRecord<String, byte[]> r : records) {
        //     TransportMessage t = serializer.deserialize(r.value());
        //     RuleMessage msg = serializer.toRuleMessage(t);
        //     Set<String> rels = t.getRelations();
        //     // ackHandle 保存 offset / partition，供 commit 使用
        //     out.add(new Envelope(UUID.randomUUID(), msg, r, rels));
        // }
        return out;
    }

    @Override
    public void commit() {
        // consumer.commitSync();  // 整批处理成功后才提交
    }

    @Override
    public String name() {
        return name;
    }
}
