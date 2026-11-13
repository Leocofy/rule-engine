package io.fire.rule.spring.flowgram.node.config;

import io.fire.rule.spring.flowgram.workflow.value.IFlowValue;

import java.util.List;

/**
 * 变量赋值/定义节点强模型：对应 FlowGram {@code data.assign}。
 */
public class VariableConfig {

    private List<AssignItem> assign;

    public List<AssignItem> getAssign() {
        return assign;
    }

    public void setAssign(List<AssignItem> assign) {
        this.assign = assign;
    }

    public static class AssignItem {

        /** declare / set；运行期都写入本节点 outputs。 */
        private String operator;

        /** 输出变量名。 */
        private String left;

        /** 赋值表达式，支持 ref / constant / expression / template。 */
        private IFlowValue right;

        public String getOperator() {
            return operator;
        }

        public void setOperator(String operator) {
            this.operator = operator;
        }

        public String getLeft() {
            return left;
        }

        public void setLeft(String left) {
            this.left = left;
        }

        public IFlowValue getRight() {
            return right;
        }

        public void setRight(IFlowValue right) {
            this.right = right;
        }
    }
}