package io.fire.rule.spring.flowgram.runtime;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** 单个 Runtime 任务的内存态；执行线程与报告读取线程并发访问。 */
final class TaskContext {

    String taskID;
    String schema;
    Map<String, Object> inputs;

    volatile String workflowStatus = "pending";
    volatile boolean terminated;
    volatile boolean canceled;
    long startTime;
    volatile long endTime;
    volatile Map<String, Object> outputs = new LinkedHashMap<>();

    final Map<String, NodeReport> reports = new ConcurrentHashMap<>();
    /** 实际执行边：sourceNodeID -> targetNodeID，供前端只描摹真实路线。 */
    final Set<String> executedEdges = ConcurrentHashMap.newKeySet();
    final List<String> nodeOrder = new CopyOnWriteArrayList<>();
    final Map<String, List<Object>> messages = new ConcurrentHashMap<>();
    final AtomicInteger snapshotSeq = new AtomicInteger();
    final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
}