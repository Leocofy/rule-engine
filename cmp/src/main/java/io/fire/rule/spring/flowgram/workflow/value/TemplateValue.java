package io.fire.rule.spring.flowgram.workflow.value;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * 模板取值：content 为含 {@code {{scope.path}}} 占位的字符串。
 */
@JsonTypeName("template")
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TemplateValue implements IFlowValue {

    @JsonProperty("content")
    private String content;

    public TemplateValue() {
    }

    public TemplateValue(String content) {
        this.content = content;
    }

    @Override
    public Object value() {
        return content;
    }

    public String template() {
        return content;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}