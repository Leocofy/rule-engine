package io.fire.rule.spring.flowgram;

import java.util.List;

/**
 * 节点级执行进度监听：把引擎观测器在<b>执行过程中</b>触发的逐节点事件透出，
 * 供 FlowGram 运行时（{@code /api/task/report}）实时点亮画布节点，而非等整链跑完一次性回填。
 *
 * <p>回调发生在引擎的执行线程上（节点同步递归执行），实现方须保证<b>线程安全</b>且<b>不抛异常</b>，
 * 以免影响正常执行链路。
 *
 * @author Cai FeiYue
 * @date 2026-11-04
 */
public interface NodeProgressListener {

    /** 空实现，供不需要进度的调用方复用。 */
    NodeProgressListener NOOP = new NodeProgressListener() {
        @Override
        public void onNodeStarted(String nodeId, String nodeType) {
        }

        @Override
        public void onNodeFinished(String nodeId, String status, String input, String payload, String error,
                                   double durationMs) {
        }
    };

    /**
     * 节点开始执行。
     *
     * @param nodeId   节点 ID
     * @param nodeType 节点类型（可能为 null）
     */
    void onNodeStarted(String nodeId, String nodeType);

    /**
     * 节点结束（成功 / 失败 / 中断）。同一节点在循环中会被多次回调。
     *
     * @param nodeId     节点 ID
     * @param status     {@link ExecutionTrace#NODE_OK} / {@link ExecutionTrace#NODE_ERROR} / {@link ExecutionTrace#NODE_ABORTED}
     * @param input      该次执行的节点输入（FlowGram inputs 解析结果，可能为 null）
     * @param payload    该次执行的消息负载快照（可能为 null）
     * @param error      错误信息（仅失败时非空）
     * @param durationMs 耗时毫秒
     */
    void onNodeFinished(String nodeId, String status, String input, String payload, String error,
                        double durationMs);

    /**
     * 节点完成路由：携带真实选中的下游节点，供前端只描摹实际执行边。
     *
     * @param nodeId 源节点 ID
     * @param targets 实际派发的目标节点 ID
     */
    default void onNodeRouted(String nodeId, List<String> targets) {
    }
}
