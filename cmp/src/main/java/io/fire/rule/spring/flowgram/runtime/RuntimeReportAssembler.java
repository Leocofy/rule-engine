package io.fire.rule.spring.flowgram.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.spring.flowgram.ExecutionTrace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime 报告组装：轮询与 SSE 共用，负责把 TaskContext 映射成官方 report 结构。 */
final class RuntimeReportAssembler {

    private final ObjectMapper objectMapper;

    RuntimeReportAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Map<String, Object> buildReportBody(TaskContext ctx) {
        Map<String, Object> workflowStatus = new LinkedHashMap<>();
        workflowStatus.put("status", ctx.workflowStatus);
        workflowStatus.put("terminated", ctx.terminated);
        workflowStatus.put("startTime", ctx.startTime);
        workflowStatus.put("endTime", ctx.endTime == 0L ? ctx.startTime : ctx.endTime);
        workflowStatus.put("timeCost", Math.max(0L, (ctx.terminated ? ctx.endTime : now()) - ctx.startTime));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", ctx.taskID);
        out.put("inputs", ctx.inputs);
        out.put("outputs", ctx.outputs);
        out.put("workflowStatus", workflowStatus);
        out.put("reports", snapshotReports(ctx));
        out.put("executedEdges", new ArrayList<>(ctx.executedEdges));
        out.put("messages", new LinkedHashMap<>(ctx.messages));
        return out;
    }

    Map<String, Object> snapshotReports(TaskContext ctx) {
        Map<String, Object> reports = new LinkedHashMap<>();
        for (String nodeId : ctx.nodeOrder) {
            NodeReport nr = ctx.reports.get(nodeId);
            if (nr == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", nr.id);
            item.put("status", nr.status);
            item.put("terminated", nr.terminated);
            item.put("startTime", nr.startTime);
            item.put("endTime", nr.endTime == 0L ? nr.startTime : nr.endTime);
            item.put("timeCost", nr.timeCost);
            item.put("snapshots", new ArrayList<>(nr.snapshots));
            reports.put(nr.id, item);
        }
        return reports;
    }

    Map<String, Object> buildOutputs(ExecutionTrace trace) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        List<ExecutionTrace.NodeTrace> nodes = trace.getNodes();
        if (!nodes.isEmpty()) {
            Object last = parseMaybeJson(nodes.get(nodes.size() - 1).getPayload());
            if (last instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) last;
                outputs.putAll(m);
            }
        }
        if (outputs.isEmpty() && trace.getMetadata() != null) {
            outputs.putAll(trace.getMetadata());
        }
        return outputs;
    }

    Map<String, Object> snapshotFrom(TaskContext ctx, String nodeId, String input,
                                     String payload, String error) {
        Object inputs = parseMaybeJson(input);
        Object outputs = parseMaybeJson(payload);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", nodeId + "-" + ctx.snapshotSeq.incrementAndGet());
        snap.put("nodeID", nodeId);
        // 对齐官方 Snapshot：inputs/outputs 必须是顶层字段，前端 NodeStatusRender 直接读取
        snap.put("inputs", inputs == null ? Collections.emptyMap() : inputs);
        snap.put("outputs", outputs == null ? Collections.emptyMap() : outputs);
        snap.put("data", Collections.emptyMap());
        if (error != null) {
            snap.put("error", error);
        }
        return snap;
    }

    private Object parseMaybeJson(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, Object.class);
        } catch (Exception e) {
            return text;
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}