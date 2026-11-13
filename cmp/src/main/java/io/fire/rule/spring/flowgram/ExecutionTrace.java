package io.fire.rule.spring.flowgram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次试跑的执行快照：整链结论 + 逐节点明细 + 事件时间线。
 *
 * <p>由 {@code cmp} 的 {@link FlowGramExecutionService} 产出，{@code /flowgram/execute} 直接序列化返回：
 * <ul>
 *   <li>{@code nodes} —— 按执行顺序排列的节点明细，同一节点执行多次就会出现多条</li>
 *   <li>{@code events} —— 完整时间线（开始 / 路由 / 完成 / 异常），用于解释"为什么走这条分支"</li>
 * </ul>
 *
 * <p>路由记录采用端口术语（{@code port}），与 FlowGram 的 {@code sourcePortID} 对齐。
 *
 * @author Cai FeiYue
 * @date 2026-10-26
 */
public class ExecutionTrace {

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    public static final String NODE_RUNNING = "RUNNING";
    public static final String NODE_OK = "OK";
    public static final String NODE_ERROR = "ERROR";
    public static final String NODE_ABORTED = "ABORTED";

    public static final String EVENT_START = "START";
    public static final String EVENT_ROUTE = "ROUTE";
    public static final String EVENT_FINISH = "FINISH";
    public static final String EVENT_ERROR = "ERROR";
    public static final String EVENT_END = "END";

    private String runId;
    private String chainId;
    private String chainName;
    private String status;
    private String messageId;
    private long startedAt;
    private double durationMs;
    private String error;
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private List<NodeTrace> nodes = new ArrayList<>();
    private List<TraceEvent> events = new ArrayList<>();

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public String getChainName() {
        return chainName;
    }

    public void setChainName(String chainName) {
        this.chainName = chainName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public double getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(double durationMs) {
        this.durationMs = durationMs;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : metadata;
    }

    public List<NodeTrace> getNodes() {
        return nodes;
    }

    public void setNodes(List<NodeTrace> nodes) {
        this.nodes = nodes == null ? new ArrayList<NodeTrace>() : nodes;
    }

    public List<TraceEvent> getEvents() {
        return events;
    }

    public void setEvents(List<TraceEvent> events) {
        this.events = events == null ? new ArrayList<TraceEvent>() : events;
    }

    /**
     * 单个节点的一次执行。
     */
    public static class NodeTrace {

        private int seq;
        private String nodeId;
        private String nodeType;
        private String status;
        private double startedAtMs;
        private double durationMs;
        private String input;
        private String payload;
        private String error;
        private List<Route> routes = new ArrayList<Route>();

        /** 仅用于计时，不参与序列化。 */
        private transient long startNanos;

        public int getSeq() {
            return seq;
        }

        public void setSeq(int seq) {
            this.seq = seq;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public String getNodeType() {
            return nodeType;
        }

        public void setNodeType(String nodeType) {
            this.nodeType = nodeType;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public double getStartedAtMs() {
            return startedAtMs;
        }

        public void setStartedAtMs(double startedAtMs) {
            this.startedAtMs = startedAtMs;
        }

        public double getDurationMs() {
            return durationMs;
        }

        public void setDurationMs(double durationMs) {
            this.durationMs = durationMs;
        }

        public String getInput() {
            return input;
        }

        public void setInput(String input) {
            this.input = input;
        }

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public List<Route> getRoutes() {
            return routes;
        }

        public void setRoutes(List<Route> routes) {
            this.routes = routes == null ? new ArrayList<Route>() : routes;
        }

        long getStartNanos() {
            return startNanos;
        }

        void setStartNanos(long startNanos) {
            this.startNanos = startNanos;
        }
    }

    /**
     * 一次路由：输出端口 + 落到的下游节点。
     */
    public static class Route {

        private String port;
        private List<String> targets = new ArrayList<String>();

        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }

        public List<String> getTargets() {
            return targets;
        }

        public void setTargets(List<String> targets) {
            this.targets = targets == null ? new ArrayList<String>() : targets;
        }
    }

    /**
     * 时间线事件：前端按 seq 顺序回放。
     */
    public static class TraceEvent {

        private int seq;
        private double offsetMs;
        private String type;
        private String nodeId;
        private String port;
        private List<String> targets;
        private String detail;

        public int getSeq() {
            return seq;
        }

        public void setSeq(int seq) {
            this.seq = seq;
        }

        public double getOffsetMs() {
            return offsetMs;
        }

        public void setOffsetMs(double offsetMs) {
            this.offsetMs = offsetMs;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }

        public List<String> getTargets() {
            return targets;
        }

        public void setTargets(List<String> targets) {
            this.targets = targets;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }
    }
}
