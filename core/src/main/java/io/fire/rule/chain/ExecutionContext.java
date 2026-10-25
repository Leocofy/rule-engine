package io.fire.rule.chain;

import io.fire.rule.model.ProcessingStack;

/**
 * 执行上下文：承载引擎内部状态，不暴露给节点。
 *
 * <p>与 {@link io.fire.rule.model.RuleMessage}（纯业务数据）分离，
 * 引擎路由时从这里取 chainId / nodeId / stack / callback。
 */
public final class ExecutionContext {

    private String chainId;
    private String nodeId;
    private final ProcessingStack stack;
    private ExecutionCallback callback;

    public ExecutionContext() {
        this.stack = new ProcessingStack();
        this.callback = ExecutionCallback.NOOP;
    }

    public ExecutionContext(String chainId, String nodeId, ProcessingStack stack, ExecutionCallback callback) {
        this.chainId = chainId;
        this.nodeId = nodeId;
        this.stack = stack != null ? stack : new ProcessingStack();
        this.callback = callback != null ? callback : ExecutionCallback.NOOP;
    }

    public ExecutionContext copy() {
        return new ExecutionContext(chainId, nodeId, stack.copy(), callback);
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
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
