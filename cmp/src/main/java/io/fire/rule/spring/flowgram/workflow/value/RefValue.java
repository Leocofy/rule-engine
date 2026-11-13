package io.fire.rule.spring.flowgram.workflow.value;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.Collections;
import java.util.List;

/**
 * 引用取值：content 为 {@code [scope, ...path]}。
 */
@JsonTypeName("ref")
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RefValue implements IFlowValue {

    @JsonProperty("content")
    private List<String> content;

    public RefValue() {
    }

    public RefValue(List<String> content) {
        this.content = content;
    }

    @Override
    public Object value() {
        return content;
    }

    public List<String> path() {
        return content == null ? Collections.<String>emptyList() : content;
    }

    public List<String> getContent() {
        return content;
    }

    public void setContent(List<String> content) {
        this.content = content;
    }
}