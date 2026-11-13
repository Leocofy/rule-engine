package io.fire.rule.spring.flowgram.node.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.fire.rule.spring.flowgram.workflow.value.IFlowValue;

import java.util.Map;

/**
 * Loop 节点强模型：对应 FlowGram {@code data.loopFor} / {@code data.loopOutputs}。
 *
 * <p>{@code loopFor} 与 {@code loopOutputs} 的值都是 {@link IFlowValue}，
 * 由 Jackson 自动反序列化，运行期由 {@code ValueResolver} 解析。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoopConfig {

    private IFlowValue loopFor;

    private Map<String, IFlowValue> loopOutputs;

    public IFlowValue getLoopFor() {
        return loopFor;
    }

    public void setLoopFor(IFlowValue loopFor) {
        this.loopFor = loopFor;
    }

    public Map<String, IFlowValue> getLoopOutputs() {
        return loopOutputs;
    }

    public void setLoopOutputs(Map<String, IFlowValue> loopOutputs) {
        this.loopOutputs = loopOutputs;
    }
}