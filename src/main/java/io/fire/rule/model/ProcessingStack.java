package io.fire.rule.model;

import java.util.LinkedList;

/**
 * 处理栈：记录消息的跨链调用路径。
 *
 * <p>两个用途：
 * <ol>
 *   <li><b>防止死循环</b>：进入嵌套链前检查 (chainId, nodeId) 是否已入栈</li>
 *   <li><b>支持回跳</b>：嵌套链执行完出栈，把结果按关系名送回调用者</li>
 * </ol>
 *
 * <p>这是低成本高收益的设计——三十行代码能避免线上无限循环事故。
 */
public final class ProcessingStack {

    private static final class Item {
        private final String chainId;
        private final String nodeId;

        Item(String chainId, String nodeId) {
            this.chainId = chainId;
            this.nodeId = nodeId;
        }
    }

    private final LinkedList<Item> stack;

    public ProcessingStack() {
        this.stack = new LinkedList<Item>();
    }

    private ProcessingStack(LinkedList<Item> stack) {
        this.stack = new LinkedList<Item>(stack);
    }

    public ProcessingStack copy() {
        return new ProcessingStack(stack);
    }

    public void push(String chainId, String nodeId) {
        stack.add(new Item(chainId, nodeId));
    }

    public boolean contains(String chainId, String nodeId) {
        for (Item item : stack) {
            if (eq(item.chainId, chainId) && eq(item.nodeId, nodeId)) {
                return true;
            }
        }
        return false;
    }

    /** 出栈并返回调用者信息；为空表示当前已是顶层链。 */
    public String[] pop() {
        if (stack.isEmpty()) {
            return null;
        }
        Item item = stack.removeLast();
        return new String[]{item.chainId, item.nodeId};
    }

    public int depth() {
        return stack.size();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
