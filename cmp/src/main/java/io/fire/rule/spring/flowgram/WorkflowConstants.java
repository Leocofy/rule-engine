package io.fire.rule.spring.flowgram;

/**
 * Description: WorkflowConstants —— FlowGram DSL 的引擎内置定义（节点类型 / 子链键 / 迭代作用域键）。
 *
 * <p>这些是 <b>flowgram 语义专属</b> 的常量，归属 starter 而非 core：core 只做通用编排、
 * 不感知任何 flowgram 定义；启用 flowgram 时由本包（{@link WorkflowSchemaLoader} 与各控制流节点）
 * 消费它们，保证模块分离。</p>
 *
 * @author Cai FeiYue
 * @date 2026/11/6
 */
public final class WorkflowConstants {

    private WorkflowConstants() {
    }

    /**
     * FlowGram 内置控制流节点类型。
     */
    public static final String TYPE_START = "start";
    public static final String TYPE_END = "end";
    public static final String TYPE_CONDITION = "condition";
    public static final String TYPE_MULTI_CONDITION = "multi-condition";
    public static final String TYPE_LOOP = "loop";
    public static final String TYPE_CODE = "code";
    public static final String TYPE_VARIABLE = "variable";

    /**
     * Loop 节点配置里挂子链 ID 的键（装载器写入）。
     */
    public static final String BLOCKS_CHAIN_ID = "__blocksChainId";
    public static final String BLOCKS_CHAIN_SUFFIX = "_blocks";
    public static final String LOCALS_SCOPE_PREFIX = "__locals_";
    public static final String CURRENT_LOCALS = "__current_locals";

    /**
     * 节点 data 中存放输入取值记录（{@code Record<field, IFlowValue>}）的字段名。
     */
    public static final String INPUTS_VALUES = "inputsValues";

    /**
     * 循环体的 locals 命名空间键：以循环节点 id 作用域，供值解析按 {@code {{loopId.item}}} 读回。
     */
    public static String localsScope(String loopId) {
        return LOCALS_SCOPE_PREFIX + loopId;
    }

}
