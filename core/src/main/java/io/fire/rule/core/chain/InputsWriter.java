package io.fire.rule.core.chain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Description: InputsWriter
 *
 * @author Cai FeiYue
 * @date 2026/11/10
 */
public class InputsWriter {

    private final Map<String, Object> inputs = new LinkedHashMap<>();

    public void write(String key, Object value) {
        synchronized (inputs) {
            this.inputs.put(key, value);
        }
    }

    public void writeAll(Map<String, Object> all) {
        if (all != null) {
            synchronized (inputs) {
                this.inputs.putAll(all);
            }
        }
    }

    @JsonValue
    public Map<String, Object> inputs() {
        return Collections.unmodifiableMap(inputs);
    }

}
