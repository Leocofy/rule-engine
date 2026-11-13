package io.fire.rule.spring.flowgram.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * FlowGram 官方 {@code IJsonSchema} 的 Java 强模型，作为后端 schema 校验的默认配置。
 *
 * <p>字段与官方定义一一对应；扩展统一放在 {@code extra}，不再使用 {@code x-*} 前缀。
 * {@code additionalProperties} 保留 {@code Object} 以兼容官方 {@code IJsonSchema} 与
 * JSON Schema 的 boolean 形式。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JsonSchemaConfig {

    private String type = "string";

    @JsonProperty("default")
    private Object defaultValue;

    private String title;

    private String description;

    @JsonProperty("enum")
    private List<Object> enumValues;

    private Map<String, JsonSchemaConfig> properties;

    private Object additionalProperties;

    private JsonSchemaConfig items;

    private List<String> required;

    @JsonProperty("$ref")
    private String ref;

    private Map<String, Object> extra;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @JsonProperty("default")
    public Object getDefaultValue() {
        return defaultValue;
    }

    @JsonProperty("default")
    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @JsonProperty("enum")
    public List<Object> getEnumValues() {
        return enumValues;
    }

    @JsonProperty("enum")
    public void setEnumValues(List<Object> enumValues) {
        this.enumValues = enumValues;
    }

    public Map<String, JsonSchemaConfig> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, JsonSchemaConfig> properties) {
        this.properties = properties;
    }

    public Object getAdditionalProperties() {
        return additionalProperties;
    }

    public void setAdditionalProperties(Object additionalProperties) {
        this.additionalProperties = additionalProperties;
    }

    public JsonSchemaConfig getItems() {
        return items;
    }

    public void setItems(JsonSchemaConfig items) {
        this.items = items;
    }

    public List<String> getRequired() {
        return required;
    }

    public void setRequired(List<String> required) {
        this.required = required;
    }

    @JsonProperty("$ref")
    public String getRef() {
        return ref;
    }

    @JsonProperty("$ref")
    public void setRef(String ref) {
        this.ref = ref;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
    }

    public Integer extraIndex() {
        Object value = extra == null ? null : extra.get("index");
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    public boolean extraWeak() {
        Object value = extra == null ? null : extra.get("weak");
        return Boolean.TRUE.equals(value);
    }

    public String extraFormComponent() {
        Object value = extra == null ? null : extra.get("formComponent");
        return value == null ? null : String.valueOf(value);
    }
}