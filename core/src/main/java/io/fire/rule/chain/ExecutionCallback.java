package io.fire.rule.chain;

/**
 * 单条消息的链路执行结果回调。
 * 由运行层（PackProcessor）实现，用于把结果回传给批次上下文。
 */
public interface ExecutionCallback {

    ExecutionCallback NOOP = new ExecutionCallback() {
        @Override
        public void onComplete() {
        }
        @Override
        public void onError(Throwable t) {
        }
    };

    void onComplete();

    void onError(Throwable t);

    default void onFanOut(int branches) {
    }

    default boolean isValid() {
        return true;
    }
}
