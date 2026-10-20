package io.fire.rule.runtime;

import java.util.List;

/**
 * 消息源抽象：把引擎与具体消息队列解耦。
 *
 * <p>内置实现可以是内存队列（测试），生产环境接 Kafka / RabbitMQ / RocketMQ。
 * 引擎只关心"拉一批、处理完提交"，不关心底层是哪种 broker。
 */
public interface MessageSource {

    /** 拉取一批消息；为空表示当前无数据。 */
    List<Envelope> poll(int maxCount);

    /** 整批处理成功，提交消费位点。 */
    void commit();

    String name();
}
