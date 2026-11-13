package io.fire.rule.spring.flowgram.workflow.value;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * 表达式取值：content 为表达式字符串。
 */
@JsonTypeName("expression")
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ExpressionValue implements IFlowValue {

    @JsonProperty("content")
    private String content;

    public ExpressionValue() {
    }

    public ExpressionValue(String content) {
        this.content = content;
    }

    @Override
    public Object value() {
        return content;
    }

    public String expression() {
        return content;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}