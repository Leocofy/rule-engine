package io.fire.rule.spring.flowgram.node.config;

import io.fire.rule.spring.flowgram.schema.JsonSchemaConfig;
import io.fire.rule.spring.flowgram.workflow.value.IFlowValue;

import java.util.Map;

/**
 * Description: NodeDataConfig
 *
 * @author Cai FeiYue
 * @date 2026/11/10
 */
public class BaseConfig {

    private String title;

    private Map<String, IFlowValue> inputsValues;

    private JsonSchemaConfig inputs;

    private JsonSchemaConfig outputs;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Map<String, IFlowValue> getInputsValues() {
        return inputsValues;
    }

    public void setInputsValues(Map<String, IFlowValue> inputsValues) {
        this.inputsValues = inputsValues;
    }

    public JsonSchemaConfig getInputs() {
        return inputs;
    }

    public void setInputs(JsonSchemaConfig inputs) {
        this.inputs = inputs;
    }

    public JsonSchemaConfig getOutputs() {
        return outputs;
    }

    public void setOutputs(JsonSchemaConfig outputs) {
        this.outputs = outputs;
    }
}
