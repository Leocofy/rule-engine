package io.fire.rule.example.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.runtime.Envelope;
import io.fire.rule.runtime.MessageSource;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 拉取式消息源示例：定时生成模拟设备消息。
 *
 * <p>生产环境替换为 Kafka / RabbitMQ 实现即可，引擎无需改动。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
public class PullSource implements MessageSource {

    private final BlockingQueue<Envelope> queue = new LinkedBlockingQueue<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ScheduledExecutorService executor;

    @PostConstruct
    public void init() {
        executor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "pull-source");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::generate, 5, 5, TimeUnit.SECONDS);
    }

    private void generate() {
        try {
            String[] types = {"test", "system", "monitor", "device"};
            int idx = ThreadLocalRandom.current().nextInt(4);
            double temp = ThreadLocalRandom.current().nextDouble(35, 50);
            double humidity = ThreadLocalRandom.current().nextDouble(20, 30);

            String payload = objectMapper.writeValueAsString(Map.of(
                    "deviceId", UUID.randomUUID().toString(),
                    "deviceType", types[idx],
                    "value", Map.of("temperature", temp, "humidity", humidity),
                    "timestamp", System.currentTimeMillis()
            ));

            Envelope envelope = new Envelope(RuleMessage.newMessage()
                    .id(UUID.randomUUID().toString())
                    .type(types[idx].toUpperCase())
                    .payload(payload)
                    .originator(types[idx])
                    .build());
            queue.add(envelope);
        } catch (Exception e) {
            // ignore
        }
    }

    public void offer(RuleMessage msg) {
        queue.offer(new Envelope(msg));
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
    }

    @Override
    public String name() {
        return "PULL";
    }

    public void destroy() {
        if (executor != null) {
            executor.shutdown();
        }
    }
}
