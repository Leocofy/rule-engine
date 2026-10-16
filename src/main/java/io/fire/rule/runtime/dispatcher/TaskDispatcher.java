package io.fire.rule.runtime.dispatcher;

/**
 * 任务分发器：决定"消息在哪个线程上被处理"。
 *
 * <p><b>这是并发模型的唯一抽象点。</b>
 * 整个引擎中真正用到它的地方只有一处——{@code PackProcessor.submitOne}
 * 里的 {@code dispatcher.execute(key, runnable)}。其余都是类型声明。
 *
 * <h3>契约（实现时必须遵守）</h3>
 * <ol>
 *   <li><b>同 key 串行</b>：同一 key 提交的多个任务必须按提交顺序执行，
 *       这是"同一设备消息有序"的保证。不要求串行语义的实现（如纯并行）要显式说明</li>
 *   <li><b>非阻塞提交</b>：{@code execute} 应尽快返回，不要同步等待任务跑完，
 *       否则会把批次主循环拖成串行</li>
 *   <li><b>异常不外溢</b>：任务内部异常由 {@code PackContext} 通过 callback 记账，
 *       分发器不应让异常打断主循环</li>
 *   <li><b>背压</b>：队列要有界。满了应该阻塞提交方或降级为调用方执行，
 *       而不是无界堆积导致 OOM</li>
 * </ol>
 */
public interface TaskDispatcher extends AutoCloseable {

    /**
     * 按 key 提交任务。
     */
    void execute(String key, Runnable task);

    @Override
    void close();

}
