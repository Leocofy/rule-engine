package io.fire.rule.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.RuleEngine;
import io.fire.rule.example.service.MockSinkHandler;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.observability.EngineObserver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Example 功能测试：验证复杂设备报文经过规则链被正确分流处理。
 *
 * <p>禁用 ExampleConfig（{@code example.enabled=false}）以使用 InMemoryMessageSource，
 * 便于测试中精确控制消息投递；禁用 auto-start，用 submitSync 同步处理。
 *
 * <p>断言走 sink 节点的投递记录（副作用），避免依赖节点统计的跨用例累积。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@SpringBootTest(properties = {
        "example.enabled=false",
        "rule.engine.auto-start=false"
})
class RuleEngineExampleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private RuleEngine ruleEngine;

    @Autowired
    private EngineObserver engineObserver;

    @Autowired
    private MockSinkHandler sinkHandler;

    @Test
    void engineReady() {
        assertNotNull(ruleEngine);
        assertNotNull(ruleEngine.chainExecutor().chain("fireAlarm"));
    }

    @Test
    void criticalTelemetryTriggersNotifyAndPersist() throws Exception {
        String deviceId = "smoke-TEST-CRITICAL";
        ruleEngine.submitSync(message(deviceId,
                payload(deviceId, 118.4, 1.12, 68.5, true, 76)));

        String notify = awaitDelivered("notify", deviceId);
        assertTrue(notify.contains("|CRITICAL|"), "严重隐患应判定为 CRITICAL: " + notify);

        String persisted = awaitDelivered("tdengine", deviceId);
        assertTrue(persisted.contains("|CRITICAL|"), "严重隐患应写入时序库: " + persisted);

        String report = statsReport();
        assertTrue(report.contains("fireAlarm/validate"), "应经过 validate 节点");
        assertTrue(report.contains("fireAlarm/riskFilter"), "应经过 riskFilter 节点");
        assertTrue(report.contains("fireAlarm/enrich"), "应经过 enrich 节点");
        assertTrue(report.contains("fireAlarm/aiJudge"), "严重隐患应触发 AI 判定节点");
    }

    @Test
    void normalTelemetryArchived() throws Exception {
        String deviceId = "smoke-TEST-NORMAL";
        ruleEngine.submitSync(message(deviceId,
                payload(deviceId, 32.6, 0.08, 4.2, true, 92)));

        String archived = awaitDelivered("archive", deviceId);
        assertTrue(archived.contains("|UNKNOWN|"), "低风险样本应直接归档: " + archived);
        assertFalse(hasRecord(sinkHandler.delivered("notify"), deviceId),
                "低风险不应触发通知");
    }

    @Test
    void offlineDeviceArchivedAsInvalid() throws Exception {
        String deviceId = "smoke-TEST-OFFLINE";
        String payload = payload(deviceId, 25.0, 0.05, 3.0, false, 12);
        ruleEngine.submitSync(message(deviceId, payload));

        String archived = awaitDelivered("archive", deviceId);
        assertTrue(archived.contains("|UNKNOWN|"), "无效数据应归档: " + archived);
        assertFalse(hasRecord(sinkHandler.delivered("tdengine"), deviceId),
                "离线设备数据不应落库");
    }

    @Test
    void collectorStatsCoverAllNodes() throws Exception {
        for (int i = 0; i < 3; i++) {
            String deviceId = "smoke-TEST-BATCH-" + i;
            ruleEngine.submitSync(message(deviceId,
                    payload(deviceId, 60 + i * 15, 0.2 + i * 0.2, 8 + i * 10, true, 80)));
        }
        String report = statsReport();
        assertTrue(report.contains("fireAlarm"), "应有节点执行统计");
        ruleEngine.registry().definitions().forEach(System.out::println);
    }

    private RuleMessage message(String deviceId, String payload) {
        return RuleMessage.newMessage()
                .type("DEVICE_TELEMETRY")
                .originator(deviceId)
                .payload(payload)
                .build();
    }

    /**
     * 构造与网关上报同构的复杂报文：事件头 + 设备档案 + 多指标遥测 + 阈值 + 最近窗口。
     */
    private String payload(String deviceId, double temperature, double smokeDensity, double coLevel,
                           boolean online, int battery) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("traceId", "trace-test-" + deviceId);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", "evt-" + deviceId);
        event.put("type", "DEVICE_TELEMETRY");
        event.put("source", "UNIT_TEST");
        event.put("reportedAt", "2026-10-22T10:15:30.512+08:00");
        event.put("reportedBy", "edge-agent-01");
        event.put("priority", "HIGH");
        root.put("event", event);

        Map<String, Object> vendor = new LinkedHashMap<>();
        vendor.put("code", "HIKVISION");
        vendor.put("model", "NP-V3");
        vendor.put("firmware", "2.4.1");

        Map<String, Object> coordinate = new LinkedHashMap<>();
        coordinate.put("lat", 39.795);
        coordinate.put("lng", 116.506);

        Map<String, Object> location = new LinkedHashMap<>();
        location.put("site", "测试仓库");
        location.put("building", "A");
        location.put("floor", 3);
        location.put("zone", "危化品存储区");
        location.put("coordinate", coordinate);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("online", online);
        status.put("battery", battery);
        status.put("signal", -63);
        status.put("lastHeartbeat", "2026-10-22T10:15:28.114+08:00");

        Map<String, Object> device = new LinkedHashMap<>();
        device.put("deviceId", deviceId);
        device.put("deviceType", "SMOKE_DETECTOR");
        device.put("vendor", vendor);
        device.put("location", location);
        device.put("status", status);
        root.put("device", device);

        Map<String, Object> telemetry = new LinkedHashMap<>();
        telemetry.put("temperature", temperature);
        telemetry.put("humidity", 21.7);
        telemetry.put("smokeDensity", smokeDensity);
        telemetry.put("coLevel", coLevel);
        telemetry.put("vibration", 0.12);
        root.put("telemetry", telemetry);

        Map<String, Object> threshold = new LinkedHashMap<>();
        threshold.put("temperature", 80.0);
        threshold.put("smokeDensity", 0.5);
        threshold.put("coLevel", 24.0);
        root.put("threshold", threshold);

        List<Map<String, Object>> window = new ArrayList<>();
        window.add(windowPoint("2026-10-22T10:13:30.512+08:00", temperature * 0.72, smokeDensity * 0.5));
        window.add(windowPoint("2026-10-22T10:14:30.512+08:00", temperature * 0.87, smokeDensity * 0.74));
        window.add(windowPoint("2026-10-22T10:15:30.512+08:00", temperature, smokeDensity));
        root.put("recentWindow", window);

        root.put("tags", Arrays.asList("warehouse", "hazmat"));
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("gatewayVersion", "3.2.0");
        ext.put("protocol", "MQTT");
        ext.put("qos", 1);
        root.put("ext", ext);
        return MAPPER.writeValueAsString(root);
    }

    private Map<String, Object> windowPoint(String ts, double temperature, double smokeDensity) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("ts", ts);
        point.put("temperature", Math.round(temperature * 100d) / 100d);
        point.put("smokeDensity", Math.round(smokeDensity * 100d) / 100d);
        return point;
    }

    private String awaitDelivered(String target, String deviceId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            for (String record : sinkHandler.delivered(target)) {
                if (hasRecord(record, deviceId)) {
                    return record;
                }
            }
            Thread.sleep(50L);
        }
        fail("等待投递超时: target=" + target + " device=" + deviceId);
        return "";
    }

    private boolean hasRecord(List<String> records, String deviceId) {
        return records.stream().anyMatch(record -> hasRecord(record, deviceId));
    }

    private boolean hasRecord(String record, String deviceId) {
        return record.startsWith(deviceId + "|");
    }

    private String statsReport() {
        if (engineObserver instanceof EngineObserver.Stats) {
            return ((EngineObserver.Stats) engineObserver).report();
        }
        return "";
    }
}
