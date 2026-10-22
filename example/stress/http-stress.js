// HTTP 黑盒压测脚本（k6）。
//
// 前置：
//   mvn -pl example -am package -DskipTests
//   java -jar example/target/rule-engine-example-1.0.0.jar --spring.profiles.active=stress
//
// 运行：
//   k6 run example/stress/http-stress.js
//   k6 run -e RATE=500 -e DURATION=3m example/stress/http-stress.js
//   k6 run -e SCENARIO=ramp -e RAMP_MAX=1000 example/stress/http-stress.js
//   k6 run -e HOT_DEVICE=true -e RATE=50 example/stress/http-stress.js
//
// 观测（另开终端）：
//   Invoke-RestMethod http://localhost:8080/stress/stats | ConvertTo-Json -Depth 6
//
// 说明：/ruleExecute 只负责入队，HTTP 延迟低不代表处理吞吐高；
// 请同时观察 stats 中的 callbackQueue、tpsWindow、timeouts、deadLetters。
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '2m';
const SCENARIO = __ENV.SCENARIO || 'steady';
const RAMP_MAX = Number(__ENV.RAMP_MAX || 1000);
const HOT_DEVICE = (__ENV.HOT_DEVICE || 'false') === 'true';
const CRITICAL_PCT = Number(__ENV.CRITICAL_PCT || 70);

const BASE = JSON.parse(open('../src/main/resources/sample-telemetry.json'));

const scenarios = SCENARIO === 'ramp'
    ? {
        ramp: {
            executor: 'ramping-arrival-rate',
            startRate: Math.max(10, Math.round(RAMP_MAX * 0.05)),
            timeUnit: '1s',
            preAllocatedVUs: 100,
            maxVUs: 2000,
            stages: [
                { target: Math.round(RAMP_MAX * 0.2), duration: '1m' },
                { target: Math.round(RAMP_MAX * 0.5), duration: '2m' },
                { target: RAMP_MAX, duration: '1m' },
                { target: 0, duration: '30s' },
            ],
            gracefulStop: '10s',
        },
    }
    : {
        steady: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: Math.max(50, Math.min(500, RATE)),
            maxVUs: 2000,
            gracefulStop: '10s',
        },
    };

export const options = {
    scenarios: scenarios,
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(99)<300'],
    },
};

export function setup() {
    const res = http.get(`${BASE_URL}/stress/stats`);
    if (res.status !== 200) {
        throw new Error('压测端点不可用，请使用 --spring.profiles.active=stress 启动应用');
    }
}

export default function () {
    const body = JSON.parse(JSON.stringify(BASE));
    const deviceId = HOT_DEVICE ? 'stress-hot-device' : `stress-${__VU}-${__ITER % 1000}`;
    const critical = Math.random() * 100 < CRITICAL_PCT;
    const now = Date.now();

    body.traceId = `trace-k6-${__VU}-${__ITER}-${now}`;
    body.event.eventId = `evt-k6-${__VU}-${__ITER}-${now}`;
    body.event.reportedAt = new Date().toISOString();
    body.event.priority = critical ? 'CRITICAL' : 'NORMAL';
    body.device.deviceId = deviceId;
    body.device.status.online = true;
    body.ext.sequence = __ITER;

    if (critical) {
        body.telemetry.temperature = 118.4;
        body.telemetry.smokeDensity = 1.12;
        body.telemetry.coLevel = 68.5;
        body.recentWindow[0].temperature = 85.3;
        body.recentWindow[1].temperature = 103.0;
        body.recentWindow[2].temperature = 118.4;
    } else {
        body.telemetry.temperature = 31.5 + Math.random() * 2;
        body.telemetry.smokeDensity = 0.05 + Math.random() * 0.1;
        body.telemetry.coLevel = 3 + Math.random() * 4;
        body.recentWindow[0].temperature = 30.2;
        body.recentWindow[1].temperature = 31.0;
        body.recentWindow[2].temperature = 31.8;
        body.recentWindow[0].smokeDensity = 0.04;
        body.recentWindow[1].smokeDensity = 0.06;
        body.recentWindow[2].smokeDensity = 0.08;
    }

    const res = http.post(`${BASE_URL}/ruleExecute`, JSON.stringify(body), {
        headers: {
            'Content-Type': 'application/json',
            'X-Source': 'K6',
        },
        tags: {
            path: 'ruleExecute',
            mode: critical ? 'critical' : 'normal',
        },
    });

    check(res, {
        accepted: (r) => r.status === 200,
    });
}
