package io.fire.rule.spi;

import io.fire.rule.model.RuleMessage;

/**
 * 消息死信处理接口
 */
public interface IDeadLetterSink {

    void accept(RuleMessage msg, int reason, Throwable error);

}