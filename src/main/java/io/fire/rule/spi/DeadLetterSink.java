package io.fire.rule.spi;

import io.fire.rule.model.RuleMessage;

/**
 * 消息死信处理接口
 */
public interface DeadLetterSink {
    void accept(RuleMessage msg, DropReason reason, Throwable error);

    enum DropReason {
        RETRY_EXHAUSTED,        // 重试次数/时间耗尽
        FAILURE_RATE_EXCEEDED,  // 失败率超阈值
        TIMEOUT,                // 超时且策略未配置重投超时项
        INTERRUPTED,            // 引擎关闭/线程中断
        UNKNOWN
    }
}