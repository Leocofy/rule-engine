package io.fire.rule.spring.flowgram.workflow.value;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * 常量取值：content 为字面量（标量 / Map / List）。
 */
@JsonTypeName("constant")
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ConstantValue implements IFlowValue {

    @JsonProperty("content")
    private Object content;

    public ConstantValue() {
    }

    public ConstantValue(Object content) {
        this.content = content;
    }

    @Override
    public Object value() {
        return content;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }
}