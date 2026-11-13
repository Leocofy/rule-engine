package io.fire.rule.spring.model;

/**
 * FlowGram 版节点定义：注册表在注册期产出的“可用节点清单”。
 *
 * <p>节点端口与输入输出 schema 由前端维护，本模型只承载
 * {@code type/title/description/category/order/version/flowNodeType}。
 *
 * @author Cai FeiYue
 * @date 2026-10-20
 */
public class NodeDefinition implements Comparable<NodeDefinition> {

    /**
     * 未显式声明排序权重时的默认值：排在声明了正数权重的节点之后。
     */
    public static final int DEFAULT_ORDER = 100;

    /** 归类：业务/原子节点（非引擎内置控制流）。 */
    public static final String FLOW_BLOCK = "block";
    public static final String FLOW_START = "start";
    public static final String FLOW_END = "end";
    public static final String FLOW_CONDITION = "condition";
    public static final String FLOW_LOOP = "loop";

    private String type;

    private String title;

    private String description;

    private String category;

    private int order;

    private String version;

    /**
     * FlowGram 节点归类：{@code start / end / condition / loop / block}。
     */
    private String flowNodeType = FLOW_BLOCK;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getFlowNodeType() {
        return flowNodeType;
    }

    public void setFlowNodeType(String flowNodeType) {
        this.flowNodeType = flowNodeType;
    }

    @Override
    public String toString() {
        return "NodeDefinition{" +
                "type='" + type + '\'' +
                ", title='" + title + '\'' +
                ", category='" + category + '\'' +
                ", flowNodeType='" + flowNodeType + '\'' +
                '}';
    }

    /**
     * 节点库展示顺序：先按排序权重，再按类型名兜底，保证多次启动顺序稳定。
     */
    @Override
    public int compareTo(NodeDefinition o) {
        int byOrder = Integer.compare(this.order, o.order);
        return byOrder != 0 ? byOrder : this.type.compareTo(o.type);
    }

}
