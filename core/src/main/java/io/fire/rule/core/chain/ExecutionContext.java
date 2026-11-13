package io.fire.rule.core.chain;

import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.model.ProcessingStack;

/**
 * 执行上下文：承载引擎内部状态，不暴露给节点。
 *
 * <p>与 {@link RuleMessage}（纯业务数据）分离，
 * 引擎路由时从这里取 chainId / nodeId / stack / callback。
 */
public final class ExecutionContext {

    private final ProcessingStack stack;
    private ExecutionCallback callback;

    /**
     * 执行过程中最近一次流转的消息快照。Loop 逐轮运行子链后据此取回累积了命名空间产出的消息。
     */
    private volatile RuleMessage lastMessage;

    public ExecutionContext() {
        this.stack = new ProcessingStack();
        this.callback = ExecutionCallback.NOOP;
    }

    public ExecutionContext(ExecutionCallback callback) {
        this.stack = new ProcessingStack();
        this.callback = callback != null ? callback : ExecutionCallback.NOOP;
    }

    public ExecutionContext(ProcessingStack stack, ExecutionCallback callback) {
        this.stack = stack != null ? stack : new ProcessingStack();
        this.callback = callback != null ? callback : ExecutionCallback.NOOP;
    }

    public ExecutionContext copy() {
        ExecutionContext child = new ExecutionContext(stack.copy(), callback);
        child.lastMessage = this.lastMessage;
        return child;
    }

    /**
     * 记录最近一次流转的消息（幂等覆盖）。
     */
    public void recordMessage(RuleMessage msg) {
        if (msg != null) {
            this.lastMessage = msg;
        }
    }

    public RuleMessage lastMessage() {
        return lastMessage;
    }

    public ProcessingStack getStack() {
        return stack;
    }

    public ExecutionCallback getCallback() {
        return callback;
    }

    public void setCallback(ExecutionCallback callback) {
        this.callback = callback;
    }

    public boolean isValid() {
        return callback.isValid();
    }

    public void onFanOut(int branches) {
        callback.onFanOut(branches);
    }

    public void pushToStack(String chainId, String nodeId) {
        stack.push(chainId, nodeId);
    }

    public boolean alreadyInStack(String chainId, String nodeId) {
        return stack.contains(chainId, nodeId);
    }

    public String[] popFromStack() {
        return stack.pop();
    }
}
