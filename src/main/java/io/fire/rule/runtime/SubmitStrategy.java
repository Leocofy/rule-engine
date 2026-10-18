package io.fire.rule.runtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提交策略：决定一批消息"以什么节奏"投给规则链。
 *
 * <p>与重试策略正交——一个管节奏，一个管容错，两者可自由组合。
 */
public interface SubmitStrategy {

    void init(List<Envelope> msgs);

    /** 尚未收到结果（处理中或已超时）的消息。 */
    Map<String, Envelope> pendingMap();

    /** 投递一批（具体数量由策略决定）。 */
    void submitAttempt(MessageConsumer consumer);

    /** 重试时只把失败/超时的消息放回。 */
    void update(Map<String, Envelope> reprocessMap);

    /** 单条成功回调，串行策略靠它解锁同 key 的下一条。 */
    void onSuccess(String id);

    /**
     * 单条失败回调。
     *
     * <p><b>必须实现</b>：串行策略若只在成功时放行，
     * 一条失败就会永久卡住该实体的后续消息，只能等批次超时——
     * 这在生产环境会表现为"某个设备的消息全部堆到超时"。
     */
    void onFailure(String id);

    void stop();

    interface MessageConsumer {
        void accept(String id, Envelope msg);
    }
}
