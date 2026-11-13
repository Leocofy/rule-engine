package io.fire.rule.spring.flowgram.workflow.value;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FlowGram 取值声明：{@code {type, content}} 的多态模型。
 *
 * <p>不同实现对应不同取值方式，Jackson 按 {@code type} 自动反序列化：
 * <ul>
 *   <li>{@link ConstantValue}：常量</li>
 *   <li>{@link RefValue}：引用 {@code [scope, ...path]}</li>
 *   <li>{@link ExpressionValue}：表达式</li>
 *   <li>{@link TemplateValue}：模板串</li>
 * </ul>
 * 解析成真实值；{@link #value()} 返回原始声明内容。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConstantValue.class, name = "constant"),
        @JsonSubTypes.Type(value = RefValue.class, name = "ref"),
        @JsonSubTypes.Type(value = ExpressionValue.class, name = "expression"),
        @JsonSubTypes.Type(value = TemplateValue.class, name = "template")
})
public interface IFlowValue extends Serializable {

    Object value();

    /**
     * 把原始 JSON 值归一化为 IFlowValue：{@code {type, content}} 白名单命中则按类型构造，
     * 否则视为 constant 字面量（兼容纯值）。
     */
    static IFlowValue from(Object raw) {
        if (raw instanceof IFlowValue) {
            return (IFlowValue) raw;
        }
        if (raw instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) raw;
            Object type = map.get("type");
            if (type instanceof String && map.containsKey("content")) {
                Object content = map.get("content");
                switch ((String) type) {
                    case "ref":
                        List<String> path = new ArrayList<>();
                        if (content instanceof List) {
                            for (Object item : (List<?>) content) {
                                path.add(item == null ? "" : String.valueOf(item));
                            }
                        } else if (content != null) {
                            path.add(String.valueOf(content));
                        }
                        return new RefValue(path);
                    case "expression":
                        return new ExpressionValue(content == null ? null : String.valueOf(content));
                    case "template":
                        return new TemplateValue(content == null ? null : String.valueOf(content));
                    case "constant":
                    default:
                        return new ConstantValue(content);
                }
            }
        }
        return new ConstantValue(raw);
    }
}