package io.fire.rule.runtime;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/** 重试策略的判决：提交 offset，还是重投一部分消息。 */
public final class ProcessingDecision {

    private static final ProcessingDecision COMMIT = new ProcessingDecision(true, Collections.<UUID, Envelope>emptyMap());

    private final boolean commit;
    private final Map<UUID, Envelope> reprocessMap;

    private ProcessingDecision(boolean commit, Map<UUID, Envelope> reprocessMap) {
        this.commit = commit;
        this.reprocessMap = reprocessMap;
    }

    public static ProcessingDecision commit() {
        return COMMIT;
    }

    public static ProcessingDecision reprocess(Map<UUID, Envelope> map) {
        return new ProcessingDecision(false, map);
    }

    public boolean isCommit() {
        return commit;
    }

    public Map<UUID, Envelope> getReprocessMap() {
        return reprocessMap;
    }
}
