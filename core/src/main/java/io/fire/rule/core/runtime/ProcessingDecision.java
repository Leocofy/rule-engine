package io.fire.rule.core.runtime;

import java.util.Collections;
import java.util.Map;

/**
 * 重试策略的判决：提交 offset，还是重投一部分消息。
 */
public final class ProcessingDecision {

    // 重试次数/时间耗尽
    public static final int RETRY_EXHAUSTED = 1;
    // 失败率超阈值
    public static final int FAILURE_RATE_EXCEEDED = 2;
    // 超时且策略未配置重投超时项
    public static final int TIMEOUT = 3;
    /**
     * 引擎关闭/线程中断
     */
    public static final int INTERRUPTED = 4;
    /**
     * 节点抛出了 fatal 异常（配置错误 / 端口写错等），重试没有意义。
     */
    public static final int FATAL_ERROR = 5;

    private static final ProcessingDecision COMMIT = new ProcessingDecision(true, Collections.emptyMap());

    private final boolean commit;
    private final Map<String, Envelope> reprocessMap;
    private final int dropReason;

    private ProcessingDecision(boolean commit, Map<String, Envelope> reprocessMap, int dropReason) {
        this.commit = commit;
        this.reprocessMap = reprocessMap;
        this.dropReason = dropReason;
    }

    private ProcessingDecision(boolean commit, Map<String, Envelope> reprocessMap) {
        this(commit, reprocessMap, 0);
    }

    /**
     * 放弃处理：提交 offset，但未成功的消息需送死信。
     */
    public static ProcessingDecision drop(int reason) {
        return new ProcessingDecision(true, Collections.emptyMap(), reason);
    }

    public static ProcessingDecision commit() {
        return COMMIT;
    }

    public static ProcessingDecision reprocess(Map<String, Envelope> map) {
        return new ProcessingDecision(false, map);
    }

    public boolean isCommit() {
        return commit;
    }

    public Map<String, Envelope> getReprocessMap() {
        return reprocessMap;
    }

    /**
     * 非指定原因 表示正常成功提交。
     *
     * <p>主循环据此判断是否要扫尾送死信——
     * <b>非 指定原因 时必须送死信，否则消息永久丢失</b>。
     */
    public int dropReason() {
        return dropReason;
    }

}
