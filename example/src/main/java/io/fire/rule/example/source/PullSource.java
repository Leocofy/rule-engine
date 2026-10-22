package io.fire.rule.example.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.example.support.TelemetrySimulator;
import io.fire.rule.model.MetaData;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.runtime.Envelope;
import io.fire.rule.runtime.MessageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 拉取式消息源示例：定时生成复杂设备遥测报文。
 *
 * <p>报文结构由 {@link TelemetrySimulator} 统一生成（事件头 + 设备档案 + 多指标遥测 +
 * 阈值 + 最近窗口 + 标签 + 扩展字段），生产环境替换为 Kafka / RabbitMQ 实现即可，
 * 引擎无需改动。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
public class PullSource implements MessageSource {

    private final BlockingQueue<Envelope> queue = new LinkedBlockingQueue<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TelemetrySimulator simulator;
    private final long intervalSeconds;
    private ScheduledExecutorService executor;

    public PullSource(TelemetrySimulator simulator, long intervalSeconds) {
        this.simulator = simulator;
        this.intervalSeconds = Math.max(1L, intervalSeconds);
    }

    public void init() {
        executor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "pull-source");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::generate, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void generate() {
        try {
            Map<String, Object> sample = simulator.nextSample();
            String payload = objectMapper.writeValueAsString(sample);
            String deviceId = simulator.deviceIdOf(sample);
            Map<String, Object> event = asMap(sample.get("event"));
            String type = event == null || event.get("type") == null
                    ? "DEVICE_TELEMETRY" : event.get("type").toString();

            MetaData metadata = new MetaData();
            metadata.put("traceId", String.valueOf(sample.get("traceId")));
            metadata.put("ingestSource", "PULL_SOURCE");
            if (event != null) {
                metadata.put("reportedAt", String.valueOf(event.get("reportedAt")));
            }

            queue.add(new Envelope(RuleMessage.newMessage()
                    .type(type)
                    .originator(deviceId)
                    .payload(payload)
                    .metaData(metadata)
                    .build()));
        } catch (Exception e) {
            // 模拟源不因单条数据生成失败而退出
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}
