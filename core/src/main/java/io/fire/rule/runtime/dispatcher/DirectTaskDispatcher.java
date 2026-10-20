package io.fire.rule.runtime.dispatcher;

/**
 * 直连分发器：在调用线程上同步执行，不排队不换线程。
 *
 * <p><b>语义</b>：天然串行（调用方就是执行方），但不提供并行度。
 *
 * <p><b>适用场景</b>：
 * <ul>
 *   <li>单元测试：结果确定、可重放，不受线程调度干扰</li>
 *   <li>批处理任务：离线跑一批数据，不关心延迟</li>
 *   <li>嵌入已有线程模型：由外部线程池驱动，引擎不再自建线程</li>
 * </ul>
 *
 * <p><b>不适合</b>：在线高吞吐场景——它会把批次主循环拖成完全串行，
 * 一条慢消息堵住整批。
 *
 * <p>注意：因为它同步阻塞，{@code PackProcessor} 的批次 await 会在
 * 提交阶段就直接跑完，超时机制形同虚设（任务要么完成要么抛异常）。
 */
public class DirectTaskDispatcher implements TaskDispatcher {

    @Override
    public void execute(String key, Runnable task) {
        task.run();
    }

    @Override
    public void close() {
    }
}
