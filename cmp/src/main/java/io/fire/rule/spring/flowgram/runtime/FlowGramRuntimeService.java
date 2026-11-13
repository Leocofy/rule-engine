package io.fire.rule.spring.flowgram.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fire.rule.core.spi.INodeRegistry;
import io.fire.rule.core.store.IChainStore;
import io.fire.rule.spring.flowgram.ExecutionTrace;
import io.fire.rule.spring.flowgram.NodeProgressListener;
import io.fire.rule.spring.flowgram.WorkflowSchemaLoader;
import io.fire.rule.spring.flowgram.execution.FlowGramExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FlowGram 运行时（Runtime）任务服务：把引擎的一次同步试跑，包装成 FlowGram Runtime API
 * 约定的"提交任务 → 轮询报告 → 取结果 / 取消"异步模型。
 *
 * <p>对齐官方契约（{@code @flowgram.ai/runtime-js} server 模式）：
 * <ul>
 *   <li>{@link #validate} —— {@code POST /api/task/validate} → {@code {valid, errors?}}</li>
 *   <li>{@link #run} —— {@code POST /api/task/run} → {@code {taskID}}（后台线程异步执行）</li>
 *   <li>{@link #report} —— {@code GET /api/task/report?taskID=} → {@code {id,inputs,outputs,workflowStatus,reports,messages}}</li>
 *   <li>{@link #subscribeReport} —— {@code GET /api/task/report/stream?taskID=} → SSE 事件流（逐节点推送 report，替代轮询）</li>
 *   <li>{@link #result} —— {@code GET /api/task/result?taskID=} → {@code outputs}</li>
 *   <li>{@link #cancel} —— {@code PUT /api/task/cancel} → {@code {success}}</li>
 * </ul>
 *
 * <p><b>执行粒度说明</b>：通过 {@link NodeProgressListener} 订阅引擎观测器在<b>执行过程中</b>
 * 触发的逐节点事件，节点状态在轮询期间被实时点亮（{@code pending → processing → succeeded/failed}），
 * 而非等整链跑完一次性回填；执行结束后再据完整 {@link ExecutionTrace} 收敛输出与工作流终态。
 *
 * @author Cai FeiYue
 * @date 2026-11-04
 */
public class FlowGramRuntimeService {

    private static final Logger LOG = LoggerFactory.getLogger(FlowGramRuntimeService.class);

    /** FlowGram WorkflowStatus 枚举值（与 runtime-interface 对齐）。 */
    private static final String ST_PENDING = "pending";
    private static final String ST_PROCESSING = "processing";
    private static final String ST_SUCCEEDED = "succeeded";
    private static final String ST_FAILED = "failed";
    private static final String ST_CANCELED = "canceled";

    private static final long DEFAULT_TIMEOUT_MS = 15000L;

    /** 已结束任务保留时长：报告/结果可继续查询，超时后清理，避免 tasks 无限增长。 */
    private static final long TASK_RETENTION_MS = 10 * 60 * 1000L;

    /** SSE 报告流的超时：给足执行 + 网络缓冲时间，超时即由容器回收连接。 */
    private static final long SSE_TIMEOUT_MS = 120000L;

    /** SSE 事件名：逐节点报告快照。 */
    private static final String SSE_EVENT_REPORT = "report";
    /** SSE 事件名：任务终止、流即将关闭。 */
    private static final String SSE_EVENT_END = "end";

    private final FlowGramExecutionService executionService;
    private final INodeRegistry registry;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final IChainStore chainStore;
    private final RuntimeReportAssembler reportAssembler;

    /** taskID → 任务上下文。 */
    private final Map<String, TaskContext> tasks = new ConcurrentHashMap<>();

    public FlowGramRuntimeService(FlowGramExecutionService executionService,
                                  INodeRegistry registry,
                                  ObjectMapper objectMapper,
                                  IChainStore chainStore) {
        this.executionService = executionService;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "rule-flowgram-runtime");
            t.setDaemon(true);
            return t;
        });
        this.chainStore = chainStore;
        this.reportAssembler = new RuntimeReportAssembler(objectMapper);
    }

    /**
     * 校验官方 Runtime 请求：优先使用 body.workflowId 对应的已保存链；带 schema 时同步保存。
     */
    public Map<String, Object> validate(String workflowId, String schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String effective = prepareSchema(workflowId, schema);
            result.put("valid", true);
            if (effectiveId(effective, workflowId) != null) {
                result.put("saved", true);
            }
        } catch (Exception e) {
            result.put("valid", false);
            result.put("errors", listOf(messageOf(e)));
        }
        return result;
    }

    /**
     * 启动任务：登记 schema 里的全部节点为 pending，后台线程执行，立即返回 taskID。
     *
     * @return {@code {taskID}}；装载失败则 {@code {code, message}}（客户端据此判错）
     */
    public Map<String, Object> run(String schema, Map<String, Object> inputs) {
        return run(null, schema, inputs);
    }

    /**
     * 启动官方 Runtime 任务：先校验并保存工作流，再异步执行。
     */
    public Map<String, Object> run(String workflowId, String schema, Map<String, Object> inputs) {
        cleanupTasks();
        TaskContext ctx;
        try {
            ctx = createTask(prepareSchema(workflowId, schema), inputs);
        } catch (RuntimeException e) {
            return error(messageOf(e));
        }
        tasks.put(ctx.taskID, ctx);
        try {
            executor.submit(() -> execute(ctx));
        } catch (RuntimeException e) {
            tasks.remove(ctx.taskID);
            return error(messageOf(e));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskID", ctx.taskID);
        return result;
    }

    /**
     * 解析官方 Runtime 请求的 schema：
     * <ul>
     *   <li>有 schema：按 workflowId 覆盖根 id，校验后更新历史 JSON；</li>
     *   <li>无 schema：按 workflowId 读取已保存 JSON；</li>
     * </ul>
     */
    private String prepareSchema(String workflowId, String schema) {
        String effective = schema;
        if (effective == null || effective.trim().isEmpty()) {
            if (workflowId == null || workflowId.trim().isEmpty()) {
                throw new IllegalArgumentException("缺少 workflowId 或 schema");
            }
            Object raw = chainStore.getChainRawData(workflowId);
            if (raw == null) {
                throw new IllegalArgumentException("规则链不存在: " + workflowId);
            }
            try {
                effective = raw instanceof String ? (String) raw : objectMapper.writeValueAsString(raw);
            } catch (Exception e) {
                throw new IllegalArgumentException("规则链序列化失败: " + messageOf(e), e);
            }
        }
        if (workflowId != null && !workflowId.trim().isEmpty()) {
            effective = withWorkflowId(effective, workflowId);
        }
        new WorkflowSchemaLoader(registry).load(effective);
        String id = effectiveId(effective, workflowId);
        if (id != null && !id.trim().isEmpty()) {
            try {
                chainStore.registerChain(effective);
            } catch (Exception e) {
                throw new IllegalArgumentException("规则链保存失败: " + messageOf(e), e);
            }
        }
        return effective;
    }

    private String withWorkflowId(String schema, String workflowId) {
        try {
            JsonNode root = objectMapper.readTree(schema);
            if (!root.isObject()) {
                throw new IllegalArgumentException("schema 必须是 JSON 对象");
            }
            ObjectNode copy = ((ObjectNode) root).deepCopy();
            copy.put("id", workflowId);
            return copy.toString();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("schema 解析失败: " + messageOf(e), e);
        }
    }

    private String effectiveId(String schema, String workflowId) {
        if (workflowId != null && !workflowId.trim().isEmpty()) {
            return workflowId;
        }
        try {
            return objectMapper.readTree(schema).path("id").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 初始化任务账本：节点先全部置 pending，执行过程中再逐节点点亮。 */
    private TaskContext createTask(String schema, Map<String, Object> inputs) {
        TaskContext ctx = new TaskContext();
        ctx.taskID = UUID.randomUUID().toString();
        ctx.schema = schema;
        ctx.inputs = inputs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(inputs);
        ctx.startTime = System.currentTimeMillis();
        ctx.workflowStatus = ST_PROCESSING;
        for (String nodeId : parseNodeIds(schema)) {
            NodeReport nr = new NodeReport();
            nr.id = nodeId;
            nr.status = ST_PENDING;
            ctx.reports.put(nodeId, nr);
            ctx.nodeOrder.add(nodeId);
        }
        return ctx;
    }

    /**
     * 生成执行报告。任务不存在时返回带 {@code code} 的错误体，令客户端停止轮询。
     */
    public Map<String, Object> report(String taskID) {
        TaskContext ctx = tasks.get(taskID);
        if (ctx == null) {
            return error("task not found: " + taskID);
        }
        return reportAssembler.buildReportBody(ctx);
    }

    /**
     * 订阅报告流（SSE）：建立一个 {@link SseEmitter}，先推一帧当前快照，之后在逐节点进度
     * 变化时由 {@link #pushReport} 增量推送，任务终止时由 {@link #completeStreams} 收尾。
     *
     * @return SSE 发射器；任务不存在时立即以错误帧结束
     */
    public SseEmitter subscribeReport(String taskID) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        TaskContext ctx = tasks.get(taskID);
        if (ctx == null) {
            try {
                emitter.send(SseEmitter.event().name(SSE_EVENT_REPORT).data(error("task not found: " + taskID)));
                emitter.send(SseEmitter.event().name(SSE_EVENT_END).data("{}"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        ctx.subscribers.add(emitter);
        emitter.onCompletion(() -> ctx.subscribers.remove(emitter));
        emitter.onTimeout(() -> {
            ctx.subscribers.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> ctx.subscribers.remove(emitter));
        // 建立即推当前快照；若任务已结束则顺手收尾，避免客户端挂起
        if (sendReport(ctx, emitter) && ctx.terminated) {
            sendEnd(ctx, emitter);
        }
        return emitter;
    }

    /** 向全部订阅者推送当前报告快照；已断开的订阅者顺带摘除。 */
    private void pushReport(TaskContext ctx) {
        if (ctx.subscribers.isEmpty()) {
            return;
        }
        ctx.subscribers.removeIf(sseEmitter -> !sendReport(ctx, sseEmitter));
    }

    /** 推送终止帧并关闭全部订阅流。 */
    private void completeStreams(TaskContext ctx) {
        for (SseEmitter emitter : ctx.subscribers) {
            sendReport(ctx, emitter);
            sendEnd(ctx, emitter);
        }
        ctx.subscribers.clear();
    }

    private boolean sendReport(TaskContext ctx, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name(SSE_EVENT_REPORT).data(reportAssembler.buildReportBody(ctx)));
            return true;
        } catch (Exception e) {
            // 客户端断开 / 序列化失败：忽略，连接由回调或下轮清理
            return false;
        }
    }

    private void sendEnd(TaskContext ctx, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name(SSE_EVENT_END).data("{}"));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    /**
     * 取最终输出：仅在任务终止后返回非空，否则返回空对象（不含 code，避免被判错误）。
     */
    public Map<String, Object> result(String taskID) {
        TaskContext ctx = tasks.get(taskID);
        if (ctx == null) {
            return error("task not found: " + taskID);
        }
        return ctx.terminated ? ctx.outputs : new LinkedHashMap<String, Object>();
    }

    /**
     * 取消：运行中置取消标志，令后台线程结束后落 {@code canceled} 态。
     */
    public Map<String, Object> cancel(String taskID) {
        TaskContext ctx = tasks.get(taskID);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (ctx == null || ctx.terminated) {
            result.put("success", false);
            return result;
        }
        ctx.canceled = true;
        result.put("success", true);
        return result;
    }

    // ================= 内部执行 =================

    private void execute(TaskContext ctx) {
        try {
            ExecutionTrace trace = executionService.execute(ctx.schema, null, writeJson(ctx.inputs),
                    runtimeMetadata(), DEFAULT_TIMEOUT_MS, newStreamListener(ctx));
            completeTask(ctx, trace);
        } catch (RuntimeException e) {
            failTask(ctx, messageOf(e));
        }
    }

    private Map<String, Object> runtimeMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("ingestSource", "FLOWGRAM_RUNTIME");
        return metadata;
    }

    private void completeTask(TaskContext ctx, ExecutionTrace trace) {
        String status = resolveWorkflowStatus(ctx, trace);
        if (!ST_SUCCEEDED.equals(status)) {
            cancelPendingReports(ctx);
        }
        String error = ST_FAILED.equals(status) || ST_CANCELED.equals(status)
                ? (trace.getError() == null ? "执行失败" : trace.getError())
                : null;
        finalizeTask(ctx, status, reportAssembler.buildOutputs(trace), error);
    }

    private void failTask(TaskContext ctx, String message) {
        cancelPendingReports(ctx);
        finalizeTask(ctx, ST_FAILED, new LinkedHashMap<>(), message);
    }

    private void finalizeTask(TaskContext ctx, String status, Map<String, Object> outputs, String error) {
        ctx.workflowStatus = status;
        ctx.outputs = outputs == null ? new LinkedHashMap<>() : outputs;
        if (error != null) {
            addMessage(ctx, "error", error, null);
        }
        ctx.terminated = true;
        ctx.endTime = System.currentTimeMillis();
        completeStreams(ctx);
        LOG.debug("FlowGram runtime 任务完成: task={} status={}", ctx.taskID, ctx.workflowStatus);
    }

    private static String resolveWorkflowStatus(TaskContext ctx, ExecutionTrace trace) {
        if (ctx.canceled) {
            return ST_CANCELED;
        }
        return ExecutionTrace.STATUS_COMPLETED.equals(trace.getStatus()) ? ST_SUCCEEDED : ST_FAILED;
    }

    private void cancelPendingReports(TaskContext ctx) {
        for (NodeReport nr : ctx.reports.values()) {
            if (ST_PENDING.equals(nr.status)) {
                nr.status = ST_CANCELED;
            }
        }
    }

    /**
     * 构造逐节点流式监听：把引擎执行过程中的节点进入 / 结束事件即时写回 {@link TaskContext}，
     * 令 {@code /api/task/report} 轮询看到 {@code pending → processing → succeeded/failed} 的实时点亮。
     */
    private NodeProgressListener newStreamListener(final TaskContext ctx) {
        return new NodeProgressListener() {
            @Override
            public void onNodeStarted(String nodeId, String nodeType) {
                if (nodeId == null) {
                    return;
                }
                NodeReport nr = reportOf(ctx, nodeId);
                nr.status = ST_PROCESSING;
                nr.terminated = false;
                if (nr.startTime == 0L) {
                    nr.startTime = now();
                }
                pushReport(ctx);
            }

            @Override
            public void onNodeFinished(String nodeId, String status, String input, String payload, String error, double durationMs) {
                if (nodeId == null) {
                    return;
                }
                NodeReport nr = reportOf(ctx, nodeId);
                nr.status = mapProgressStatus(status);
                nr.terminated = true;
                long end = now();
                if (nr.startTime == 0L) {
                    nr.startTime = end - (long) durationMs;
                }
                nr.endTime = end;
                nr.timeCost = (long) durationMs;
                nr.snapshots.add(reportAssembler.snapshotFrom(ctx, nodeId, input, payload, error));
                pushReport(ctx);
            }

            @Override
            public void onNodeRouted(String nodeId, List<String> targets) {
                if (nodeId == null || targets == null) {
                    return;
                }
                for (String target : targets) {
                    if (target != null) {
                        ctx.executedEdges.add(nodeId + "->" + target);
                    }
                }
                pushReport(ctx);
            }
        };
    }

    /** 取或建节点报告，并保证新节点进入有序列表（并发安全）。 */
    private NodeReport reportOf(TaskContext ctx, String nodeId) {
        NodeReport nr = ctx.reports.get(nodeId);
        if (nr == null) {
            nr = new NodeReport();
            nr.id = nodeId;
            NodeReport prev = ctx.reports.putIfAbsent(nodeId, nr);
            if (prev != null) {
                nr = prev;
            } else {
                ctx.nodeOrder.add(nodeId);
            }
        }
        return nr;
    }

    /** 执行进度状态 → FlowGram 节点状态：仅区分成功/失败/取消，其余归为 processing。 */
    private static String mapProgressStatus(String traceStatus) {
        if (ExecutionTrace.NODE_OK.equals(traceStatus)) {
            return ST_SUCCEEDED;
        }
        if (ExecutionTrace.NODE_ERROR.equals(traceStatus)) {
            return ST_FAILED;
        }
        if (ExecutionTrace.NODE_ABORTED.equals(traceStatus)) {
            return ST_CANCELED;
        }
        return ST_PROCESSING;
    }

    private void addMessage(TaskContext ctx, String type, String message, String nodeID) {
        Map<String, Object> msg = new LinkedHashMap<String, Object>();
        msg.put("id", UUID.randomUUID().toString());
        msg.put("type", type);
        msg.put("message", message);
        if (nodeID != null) {
            msg.put("nodeID", nodeID);
        }
        msg.put("timestamp", System.currentTimeMillis());
        List<Object> list = ctx.messages.get(type);
        if (list == null) {
            list = new CopyOnWriteArrayList<>();
            ctx.messages.put(type, list);
        }
        list.add(msg);
    }

    private List<String> parseNodeIds(String schema) {
        try {
            JsonNode root = objectMapper.readTree(schema);
            Set<String> ids = new LinkedHashSet<>();
            collectNodeIds(root.path("nodes"), ids);
            return new ArrayList<>(ids);
        } catch (Exception e) {
            throw new IllegalArgumentException("schema 解析失败：" + messageOf(e), e);
        }
    }

    private void collectNodeIds(JsonNode nodes, Set<String> ids) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            String id = node.path("id").asText(null);
            if (id != null) {
                ids.add(id);
            }
            collectNodeIds(node.path("blocks"), ids);
        }
    }

    /** 清理超过保留期的已结束任务，避免 tasks 与 SSE 快照无限增长。 */
    private void cleanupTasks() {
        long cutoff = now() - TASK_RETENTION_MS;
        tasks.entrySet().removeIf(entry -> {
            TaskContext ctx = entry.getValue();
            return ctx.terminated && ctx.endTime > 0 && ctx.endTime < cutoff;
        });
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        // 客户端以是否存在 code 字段判定错误，故错误体必须带 code
        body.put("code", 500);
        body.put("message", message);
        return body;
    }

    private static List<String> listOf(String s) {
        List<String> list = new ArrayList<String>();
        list.add(s);
        return list;
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
