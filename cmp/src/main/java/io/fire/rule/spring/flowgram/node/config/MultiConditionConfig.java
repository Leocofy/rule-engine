package io.fire.rule.spring.flowgram.node.config;

import java.util.List;

/**
 * 多条件分支强模型：对应 FlowGram {@code data.branch}。
 *
 * <p>每个 branch 是一个端口，logic=and 表示组内条件全部成立，
 * logic=or 表示组内任意条件成立；端口 id 为 {@code branch.<index>}。
 */
public class MultiConditionConfig {

    private List<Branch> branch;

    public List<Branch> getBranch() {
        return branch;
    }

    public void setBranch(List<Branch> branch) {
        this.branch = branch;
    }

    public static class Branch {

        private String logic = "and";

        private List<ConditionItem> conditions;

        public String getLogic() {
            return logic;
        }

        public void setLogic(String logic) {
            this.logic = logic;
        }

        public List<ConditionItem> getConditions() {
            return conditions;
        }

        public void setConditions(List<ConditionItem> conditions) {
            this.conditions = conditions;
        }
    }

    public static class ConditionItem {

        private String key;

        private ConditionConfig.ConditionValue value;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public ConditionConfig.ConditionValue getValue() {
            return value;
        }

        public void setValue(ConditionConfig.ConditionValue value) {
            this.value = value;
        }
    }
}