package io.fire.rule.runtime.dispatcher;

/**
 * Actor 风格分发器的适配骨架。
 *
 * <p>如果你想换成真正的 Actor 框架（Pekko / Actor4j / POJO-actor），
 * 实现本类即可，引擎其余部分零改动。
 *
 * <p><b>适配要点</b>：Actor 的语义与 {@link TaskDispatcher} 高度吻合：
 * <pre>
 *   TaskDispatcher.execute(key, task)   ≈   ActorRef.tell(message)
 *   同 key 串行                          ≈   同 Actor 邮箱串行
 * </pre>
 * 所以适配层通常很薄——核心就是"按 key 找到/创建 Actor，把 Runnable 包成消息投递"。
 *
 * <p><b>换之前请三思</b>：Actor 框架会引入监督树、远程通信、配置体系，
 * 且调试时调用栈断裂。除非你确实需要这些，否则
 * {@link KeyOrderedDispatcher} 用不到 100 行就提供了同样的串行保证。
 *
 * <p>本类保留为「如何接外部 Actor 框架」的示范，默认实现退化为直连。
 */
public class ActorStyleDispatcher implements TaskDispatcher {

    /**
     * Actor 句柄抽象：替换时实现它，内部持真实框架的 ActorRef。
     */
    public interface ActorHandle {
        void tell(Runnable task);
    }

    /**
     * Actor 解析器：key → Actor。替换时实现"找不到就创建"的逻辑。
     */
    public interface ActorResolver {
        ActorHandle resolveOrCreate(String key);
    }

    private final ActorResolver resolver;

    public ActorStyleDispatcher(ActorResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void execute(String key, Runnable task) {
        resolver.resolveOrCreate(key).tell(task);
    }

    @Override
    public void close() {
    }
}
