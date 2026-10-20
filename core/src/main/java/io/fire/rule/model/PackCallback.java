package io.fire.rule.model;

/**
 * 批次回调桥梁：把单个节点的 ack / failure 回传给消息所在的批次上下文。
 *
 * <p>这是"整批处理"能成立的关键——节点只管报告自己的结果，
 * 由批次上下文统一决定 commit 还是重投。
 */
public interface PackCallback {

    PackCallback EMPTY = new PackCallback() {
        @Override
        public void onSuccess() {
        }

        @Override
        public void onFailure(Throwable t) {
        }
    };

    void onSuccess();

    void onFailure(Throwable t);

    /**
     * 声明本条消息将派生出 n 个并行分支。
     *
     * <p>必须由编排层在 dispatch 之前调用：否则第一个分支完成就会
     * 把整条消息判为成功，其余分支的结果被丢弃（fan-out 竞态）。
     */
    default void onFanOut(int branches) {
    }

    /**
     * 批次超时或取消后，仍在飞行的消息会被标记无效。
     * 异步节点在执行耗时操作前应检查该标记，避免做无用功。
     */
    default boolean isValid() {
        return true;
    }
}
