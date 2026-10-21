package io.fire.rule.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.runtime.Envelope;
import io.fire.rule.runtime.MessageSource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;

/**
 * Description: PullSource
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@Component
public class PullSource implements MessageSource {

    private final BlockingQueue<Envelope> queue = new LinkedBlockingQueue<>();

    @PostConstruct
    public void init() {
        try (ScheduledExecutorService executor = Executors.newScheduledThreadPool(1)) {
            executor.schedule(() -> {
                String[] types = new String[] {"test", "system", "monitor", "device"};
                int random = new Random().nextInt(4);

                String payload;
                try {
                    payload = new ObjectMapper().writeValueAsString(Map.of(
                            "deviceId", UUID.randomUUID().toString(),
                            "deviceType", types[random],
                            "value", Map.of("temperature", new Random().nextDouble(35, 50), "humidity", new Random().nextDouble(20, 30)),
                            "timestamp", System.currentTimeMillis()
                    ));
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }

                Envelope envelope = new Envelope(RuleMessage.newMessage()
                        .id(UUID.randomUUID().toString())
                        .type(types[random].toUpperCase())
                        .payload(payload)
                        .originator(types[random])
                        .build());
                queue.add(envelope);
            }, 10, TimeUnit.SECONDS);
        }
    }

    @Override
    public List<Envelope> poll(int maxCount) {
        List<Envelope> batch = new ArrayList<>(maxCount);
        queue.drainTo(batch, maxCount);
        if (batch.isEmpty()) {
            try {
                Envelope first = queue.poll(50, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, maxCount - 1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return batch;
    }

    @Override
    public void commit() {
        System.out.println("Committing messages...");
    }

    @Override
    public String name() {
        return "PULL";
    }
}
