package io.fire.rule.example.support;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 复杂设备遥测数据模拟器。
 *
 * <p>模拟真实 IoT 网关上报的报文结构：事件头、设备档案（含厂商/位置/状态）、
 * 多指标遥测、阈值配置、最近窗口、标签与扩展字段。
 * 每个字段都有明确的业务含义，规则链可以直接按嵌套路径取值。
 *
 * <p>随机场景：正常（60%）、预警（25%）、严重（15%），另有 3% 概率设备离线，
 * 用于覆盖校验节点的异常分支。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
@Component
public class TelemetrySimulator {

    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private static final List<DeviceProfile> PROFILES = Arrays.asList(
            new DeviceProfile("smoke-A3-021", "SMOKE_DETECTOR", "北京亦庄仓储中心",
                    "A", 3, "危化品存储区", "HIKVISION", "NP-V3", "2.4.1",
                    80.0, 0.50, 24.0, Arrays.asList("warehouse", "hazmat", "priority-site")),
            new DeviceProfile("heat-B1-004", "HEAT_SENSOR", "北京亦庄仓储中心",
                    "B", 1, "高压配电间", "Honeywell", "HD-200", "1.8.6",
                    75.0, 0.40, 20.0, Arrays.asList("warehouse", "electrical")),
            new DeviceProfile("gas-C2-118", "GAS_DETECTOR", "天津港冷链仓",
                    "C", 2, "制冷机房", "SIEMENS", "SG-9", "3.1.0",
                    70.0, 0.35, 30.0, Arrays.asList("cold-chain", "ammonia-risk")),
            new DeviceProfile("smoke-D1-007", "SMOKE_DETECTOR", "上海临港数据中心",
                    "D", 1, "锂电池储能间", "HIKVISION", "NP-V3", "2.4.3",
                    85.0, 0.55, 26.0, Arrays.asList("data-center", "lithium-battery"))
    );

    /**
     * 生成一条完整的模拟遥测报文（JSON 结构对应的 Map）。
     */
    public Map<String, Object> nextSample() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        DeviceProfile profile = PROFILES.get(random.nextInt(PROFILES.size()));
        Scenario scenario = pickScenario(random);
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        boolean online = random.nextInt(100) >= 3;

        double temperature = round2(scenario.temperature(profile, random));
        double smokeDensity = round2(scenario.smokeDensity(profile, random));
        double coLevel = round2(scenario.coLevel(profile, random));
        double humidity = round2(random.nextDouble(18, 62));
        double vibration = round2(random.nextDouble(0.01, 0.30));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("traceId", "trace-" + now.toInstant().toEpochMilli() + "-" + shortId());
        root.put("event", event(profile, scenario, now, random));
        root.put("device", device(profile, online, random));
        root.put("telemetry", telemetry(temperature, humidity, smokeDensity, coLevel, vibration));
        root.put("threshold", threshold(profile));
        root.put("recentWindow", recentWindow(now, scenario, temperature, smokeDensity));
        root.put("tags", profile.tags);
        root.put("ext", ext(random));
        return root;
    }

    /**
     * 从报文结构中取出设备 ID，作为消息分区键。
     */
    public String deviceIdOf(Map<String, Object> sample) {
        Object device = sample.get("device");
        if (device instanceof Map) {
            Object deviceId = ((Map<?, ?>) device).get("deviceId");
            return deviceId == null ? null : deviceId.toString();
        }
        return null;
    }

    private Map<String, Object> event(DeviceProfile profile, Scenario scenario,
                                      OffsetDateTime now, ThreadLocalRandom random) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", "evt-" + shortId());
        event.put("type", "DEVICE_TELEMETRY");
        event.put("source", "EDGE_GATEWAY");
        event.put("reportedAt", now.format(ISO_MILLIS));
        event.put("reportedBy", "edge-agent-" + String.format("%02d", random.nextInt(1, 6)));
        event.put("priority", scenario.name());
        event.put("siteCode", profile.building + "-" + profile.floor);
        return event;
    }

    private Map<String, Object> device(DeviceProfile profile, boolean online, ThreadLocalRandom random) {
        Map<String, Object> vendor = new LinkedHashMap<>();
        vendor.put("code", profile.vendor);
        vendor.put("model", profile.model);
        vendor.put("firmware", profile.firmware);

        Map<String, Object> location = new LinkedHashMap<>();
        location.put("site", profile.site);
        location.put("building", profile.building);
        location.put("floor", profile.floor);
        location.put("zone", profile.zone);
        location.put("coordinate", coordinate(random));

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("online", online);
        status.put("battery", online ? random.nextInt(30, 100) : random.nextInt(5, 20));
        status.put("signal", random.nextInt(-95, -45));
        status.put("lastHeartbeat", OffsetDateTime.now(ZONE).minusSeconds(random.nextInt(1, 120))
                .format(ISO_MILLIS));

        Map<String, Object> device = new LinkedHashMap<>();
        device.put("deviceId", profile.deviceId);
        device.put("deviceType", profile.deviceType);
        device.put("vendor", vendor);
        device.put("location", location);
        device.put("status", status);
        device.put("installedAt", "2024-06-18");
        return device;
    }

    private Map<String, Object> coordinate(ThreadLocalRandom random) {
        Map<String, Object> coordinate = new LinkedHashMap<>();
        coordinate.put("lat", round6(39.70 + random.nextDouble(0, 0.30)));
        coordinate.put("lng", round6(116.20 + random.nextDouble(0, 0.60)));
        return coordinate;
    }

    private Map<String, Object> telemetry(double temperature, double humidity, double smokeDensity,
                                          double coLevel, double vibration) {
        Map<String, Object> telemetry = new LinkedHashMap<>();
        telemetry.put("temperature", temperature);
        telemetry.put("humidity", humidity);
        telemetry.put("smokeDensity", smokeDensity);
        telemetry.put("coLevel", coLevel);
        telemetry.put("vibration", vibration);
        return telemetry;
    }

    private Map<String, Object> threshold(DeviceProfile profile) {
        Map<String, Object> threshold = new LinkedHashMap<>();
        threshold.put("temperature", profile.tempThreshold);
        threshold.put("smokeDensity", profile.smokeThreshold);
        threshold.put("coLevel", profile.coThreshold);
        return threshold;
    }

    private List<Map<String, Object>> recentWindow(OffsetDateTime now, Scenario scenario,
                                                   double temperature, double smokeDensity) {
        double startFactor = scenario == Scenario.CRITICAL ? 0.72 : 0.96;
        double middleFactor = scenario == Scenario.CRITICAL ? 0.87 : 0.98;
        List<Map<String, Object>> window = new ArrayList<>();
        window.add(windowPoint(now.minusMinutes(2), temperature * startFactor, smokeDensity * 0.50));
        window.add(windowPoint(now.minusMinutes(1), temperature * middleFactor, smokeDensity * 0.74));
        window.add(windowPoint(now, temperature, smokeDensity));
        return window;
    }

    private Map<String, Object> windowPoint(OffsetDateTime time, double temperature, double smokeDensity) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("ts", time.format(ISO_MILLIS));
        point.put("temperature", round2(temperature));
        point.put("smokeDensity", round2(smokeDensity));
        return point;
    }

    private Map<String, Object> ext(ThreadLocalRandom random) {
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("gatewayVersion", "3.2.0");
        ext.put("protocol", "MQTT");
        ext.put("qos", 1);
        ext.put("sequence", random.nextLong(100000L, 999999L));
        return ext;
    }

    private Scenario pickScenario(ThreadLocalRandom random) {
        int roll = random.nextInt(100);
        if (roll < 60) {
            return Scenario.NORMAL;
        }
        return roll < 85 ? Scenario.WARNING : Scenario.CRITICAL;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static double round6(double value) {
        return Math.round(value * 1000000d) / 1000000d;
    }

    /**
     * 随机场景：不同场景生成不同量级的遥测值。
     */
    private enum Scenario {
        NORMAL {
            @Override
            double temperature(DeviceProfile profile, ThreadLocalRandom random) {
                return random.nextDouble(22, 38);
            }

            @Override
            double smokeDensity(DeviceProfile profile, ThreadLocalRandom random) {
                return random.nextDouble(0.02, 0.18);
            }

            @Override
            double coLevel(DeviceProfile profile, ThreadLocalRandom random) {
                return random.nextDouble(2, 8);
            }
        },
        WARNING {
            @Override
            double temperature(DeviceProfile profile, ThreadLocalRandom random) {
                return profile.tempThreshold * random.nextDouble(0.85, 1.08);
            }

            @Override
            double smokeDensity(DeviceProfile profile, ThreadLocalRandom random) {
                return profile.smokeThreshold * random.nextDouble(0.70, 1.15);
            }

            @Override
            double coLevel(DeviceProfile profile, ThreadLocalRandom random) {
                return profile.coThreshold * random.nextDouble(0.55, 1.05);
            }
        },
        CRITICAL {
            @Override
            double temperature(DeviceProfile profile, ThreadLocalRandom random) {
                return profile.tempThreshold * random.nextDouble(1.15, 1.55);
            }

            @Override
            double smokeDensity(DeviceProfile profile, ThreadLocalRandom random) {
                return profile.smokeThreshold * random.nextDouble(1.20, 2.20);
            }

            @Override
            double coLevel(DeviceProfile profile, ThreadLocalRandom random) {
                return profile.coThreshold * random.nextDouble(1.20, 2.60);
            }
        };

        abstract double temperature(DeviceProfile profile, ThreadLocalRandom random);

        abstract double smokeDensity(DeviceProfile profile, ThreadLocalRandom random);

        abstract double coLevel(DeviceProfile profile, ThreadLocalRandom random);
    }

    /**
     * 设备档案：一台设备除遥测值外的静态信息与阈值。
     */
    private static final class DeviceProfile {
        private final String deviceId;
        private final String deviceType;
        private final String site;
        private final String building;
        private final int floor;
        private final String zone;
        private final String vendor;
        private final String model;
        private final String firmware;
        private final double tempThreshold;
        private final double smokeThreshold;
        private final double coThreshold;
        private final List<String> tags;

        private DeviceProfile(String deviceId, String deviceType, String site, String building,
                              int floor, String zone, String vendor, String model, String firmware,
                              double tempThreshold, double smokeThreshold, double coThreshold,
                              List<String> tags) {
            this.deviceId = deviceId;
            this.deviceType = deviceType;
            this.site = site;
            this.building = building;
            this.floor = floor;
            this.zone = zone;
            this.vendor = vendor;
            this.model = model;
            this.firmware = firmware;
            this.tempThreshold = tempThreshold;
            this.smokeThreshold = smokeThreshold;
            this.coThreshold = coThreshold;
            this.tags = tags;
        }
    }
}
