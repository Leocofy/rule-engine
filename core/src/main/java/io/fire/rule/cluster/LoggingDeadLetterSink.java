package io.fire.rule.cluster;

import io.fire.rule.model.RuleMessage;
import io.fire.rule.spi.IDeadLetterSink;

public class LoggingDeadLetterSink implements IDeadLetterSink {
    @Override
    public void accept(RuleMessage msg, int reason, Throwable error) {
        System.err.println("[DLQ] 消息被放弃 reason=" + reason + "[" + dropMessage(reason) + "]"
                + " id=" + msg.getId()
                + " originator=" + msg.getOriginator()
                + " type=" + msg.getType()
                + " payload=" + msg.getPayload()
                + (error != null ? " error=" + error.getMessage() : " (超时无异常)"));
    }

    /**
     * 死信产生原因
     */
    public static String dropMessage(int dropReason) {
        switch (dropReason) {
            case 1:
                return "重试次数/时间耗尽";
            case 2:
                return "失败率超阈值";
            case 3:
                return "超时且策略未配置重投超时项";
            case 4:
                return "引擎关闭/线程中断";
            default:
                return "未知原因";
        }
    }
}