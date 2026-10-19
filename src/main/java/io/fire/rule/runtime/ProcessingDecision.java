package io.fire.rule.runtime;

import io.fire.rule.spi.DeadLetterSink;

import java.util.Collections;
import java.util.Map;

/**
 * 重试策略的判决：提交 offset，还是重投一部分消息。
 */
public final class ProcessingDecision {

    private static final ProcessingDecision COMMIT = new ProcessingDecision(true, Collections.emptyMap(), null);

    private final boolean commit;
    private final Map<String, Envelope> reprocessMap;
    private final DeadLetterSink.DropReason dropReason;

    private ProcessingDecision(boolean commit, Map<String, Envelope> reprocessMap, DeadLetterSink.DropReason dropReason) {
        this.commit = commit;
        this.reprocessMap = reprocessMap;
        this.dropReason = dropReason;
    }

    public static ProcessingDecision commit() {
        return COMMIT;
    }

    public static ProcessingDecision reprocess(Map<String, Envelope> map) {
        return new ProcessingDecision(false, map, null);
    }

    public boolean isCommit() {
        return commit;
    }

    public Map<String, Envelope> getReprocessMap() {
        return reprocessMap;
    }
}
