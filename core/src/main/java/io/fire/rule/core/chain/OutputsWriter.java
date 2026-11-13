package io.fire.rule.core.chain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Description: OutputsWriter
 *
 * @author Cai FeiYue
 * @date 2026/11/11
 */
public class OutputsWriter {

    private final Map<String, Object> outputs = new LinkedHashMap<>();

    public void write(String key, Object value) {
        synchronized (outputs) {
            this.outputs.put(key, value);
        }
    }

    public void writeAll(Map<String, Object> all) {
        if (all != null) {
            synchronized (outputs) {
                this.outputs.putAll(all);
            }
        }
    }

    @JsonValue
    public Map<String, Object> outputs() {
        return Collections.unmodifiableMap(outputs);
    }

}
