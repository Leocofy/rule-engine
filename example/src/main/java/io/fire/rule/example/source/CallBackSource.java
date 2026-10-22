package io.fire.rule.example.source;

import io.fire.rule.runtime.Envelope;
import io.fire.rule.runtime.MessageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Description: CallBackSource
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@Component
public class CallBackSource implements MessageSource {

    private static final Logger log = LoggerFactory.getLogger(CallBackSource.class);
    private final LinkedBlockingQueue<Envelope> queue = new LinkedBlockingQueue<>();

    private final Map<String, List<Envelope>> padding = new ConcurrentHashMap<>();

    private final ThreadLocal<String> paddingId = new ThreadLocal<>();

    public void add(Envelope envelope) {
        queue.add(envelope);
    }

    /**
     * 尚未被消费的消息数，用于压测时判断入口是否持续积压。
     */
    public int size() {
        return queue.size();
    }

    /**
     * 已 poll 但尚未 commit 的批次数量；持续大于 1 说明提交环节未正常推进。
     */
    public int pendingBatches() {
        return padding.size();
    }

    @Override
    public List<Envelope> poll(int maxCount) {
        List<Envelope> envelopes = new ArrayList<>();
        for (int i = 0; i < maxCount; i++) {
            Envelope envelope = queue.poll();
            if (envelope == null) {
                break;
            }
            envelopes.add(envelope);
        }

        if (!envelopes.isEmpty()) {
            paddingId.set(UUID.randomUUID().toString());
            log.debug("padding: {} size={}", paddingId.get(), envelopes.size());
            padding.put(paddingId.get(), envelopes);
        }
        return envelopes;
    }

    @Override
    public void commit() {
        if (paddingId.get() != null) {
            padding.remove(paddingId.get());
            log.debug("commit: {}", paddingId.get());
        }
        paddingId.remove();
    }

    @Override
    public String name() {
        return "CALLBACK";
    }
}
