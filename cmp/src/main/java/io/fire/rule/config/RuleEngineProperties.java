package io.fire.rule.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 规则引擎配置属性。
 *
 * <p>在 application.yaml / application.properties 中通过前缀 {@code rule.engine} 配置：
 * <pre>
 * rule:
 *   engine:
 *     enabled: true
 *     default-chain-id: fireAlarm
 *     chain-config-path: fire-alarm-chain.json
 *     poll-size: 256
 *     pack-timeout-ms: 3000
 *     auto-start: true
 *     expression-engine: aviator
 * </pre>
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@ConfigurationProperties(prefix = "rule.engine")
public class RuleEngineProperties {

    private boolean enabled = true;

    private int pollSize = 256;

    private long packTimeoutMs = 2000L;

    private String expressionEngine = "aviator";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPollSize() {
        return pollSize;
    }

    public void setPollSize(int pollSize) {
        this.pollSize = pollSize;
    }

    public long getPackTimeoutMs() {
        return packTimeoutMs;
    }

    public void setPackTimeoutMs(long packTimeoutMs) {
        this.packTimeoutMs = packTimeoutMs;
    }

    public String getExpressionEngine() {
        return expressionEngine;
    }

    public void setExpressionEngine(String expressionEngine) {
        this.expressionEngine = expressionEngine;
    }
}
