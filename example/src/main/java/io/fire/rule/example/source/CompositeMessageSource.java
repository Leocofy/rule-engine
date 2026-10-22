package io.fire.rule.example.source;

import io.fire.rule.runtime.Envelope;
import io.fire.rule.runtime.MessageSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 组合消息源：把 HTTP 回调入口与拉取式模拟网关合并成一个引擎消费通道。
 *
 * <p>真实项目里通常按来源拆分独立队列（不同优先级、不同重试策略）；
 * 这里合并是为了让示例既可被接口触发，也能自动产生模拟流量。
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
public class CompositeMessageSource implements MessageSource {

    private final List<MessageSource> delegates;

    public CompositeMessageSource(MessageSource... delegates) {
        this.delegates = Arrays.asList(delegates);
    }

    @Override
    public List<Envelope> poll(int maxCount) {
        List<Envelope> batch = new ArrayList<>(maxCount);
        for (MessageSource delegate : delegates) {
            if (batch.size() >= maxCount) {
                break;
            }
            List<Envelope> part = delegate.poll(maxCount - batch.size());
            if (part != null && !part.isEmpty()) {
                batch.addAll(part);
            }
        }
        return batch;
    }

    @Override
    public void commit() {
        for (MessageSource delegate : delegates) {
            delegate.commit();
        }
    }

    @Override
    public String name() {
        return "COMPOSITE";
    }
}
