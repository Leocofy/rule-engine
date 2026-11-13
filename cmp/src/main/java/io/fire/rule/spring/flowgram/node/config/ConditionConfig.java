package io.fire.rule.spring.flowgram.node.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.fire.rule.spring.flowgram.workflow.value.IFlowValue;

import java.util.List;

/**
 * Condition 节点强模型：对应 FlowGram {@code data.conditions}。
 *
 * <p>{@code left/right} 声明为 {@link IFlowValue}，由 Jackson 按 {@code type} 自动反序列化为
 * 具体取值类型；运行期节点调用 {@code ValueResolver} 解析成真实值。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConditionConfig {

    private List<ConditionItem> conditions;

    public List<ConditionItem> getConditions() {
        return conditions;
    }

    public void setConditions(List<ConditionItem> conditions) {
        this.conditions = conditions;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConditionItem {

        private String key;

        private ConditionValue value;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public ConditionValue getValue() {
            return value;
        }

        public void setValue(ConditionValue value) {
            this.value = value;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConditionValue {

        private IFlowValue left;

        private String operator;

        private IFlowValue right;

        public IFlowValue getLeft() {
            return left;
        }

        public void setLeft(IFlowValue left) {
            this.left = left;
        }

        public String getOperator() {
            return operator;
        }

        public void setOperator(String operator) {
            this.operator = operator;
        }

        public IFlowValue getRight() {
            return right;
        }

        public void setRight(IFlowValue right) {
            this.right = right;
        }
    }
}