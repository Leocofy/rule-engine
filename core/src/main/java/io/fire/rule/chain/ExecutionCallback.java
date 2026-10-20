package io.fire.rule.chain;

/**
 * 单条消息的链路执行结果回调。
 * 由运行层（PackProcessor）实现，用于把结果回传给批次上下文。
 */
public interface ExecutionCallback {

    /** 空实现：用于 tellSelf 等无批次归属的场景。 */
    ExecutionCallback NOOP = new ExecutionCallback() {
        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Throwable t) {
        }
    };

    /** 链路走完（被某个节点 ack，或走到尽头无人接手）。 */
    void onComplete();

    void onError(Throwable t);
}
