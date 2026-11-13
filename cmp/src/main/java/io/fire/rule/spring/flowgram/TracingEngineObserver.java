package io.fire.rule.spring.flowgram;

import io.fire.rule.core.chain.RuleChain;
import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.observability.EngineObserver;
import io.fire.rule.core.observability.NodeDelta;
import io.fire.rule.core.store.IChainStore;
import io.fire.rule.core.util.JsonUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 试跑专用观测器：把节点的进入 / 路由 / 耗时 / 异常收集成一份 {@link ExecutionTrace}。
 *
 * <p>与 {@link EngineObserver.Stats} 的分工：Stats 是长期累计的全局统计，
 * 这里只服务"一次试跑"，跑完即可丢弃，因此可以放心记录消息内容与逐节点耗时。
 *
 * <p><b>线程模型</b>：节点是同步递归执行的（tellNext → dispatch → 下一个节点），
 * 所以用 ThreadLocal 栈记录"当前正在执行哪个节点"，路由事件才能归属到正确的节点。
 * 节点把工作丢到异步线程时栈为空，此时只记录事件、不归属节点，不会串味。
 *
 * @author Cai FeiYue
 * @date 2026-10-26
 */
public class TracingEngineObserver implements EngineObserver {

    /** 报文预览上限：够看清结构即可，避免一次试跑把响应撑大。 */
    private static final int PAYLOAD_LIMIT = 900;

    private final IChainStore chainStore;
    private final ExecutionTrace trace = new ExecutionTrace();
    private final long startNanos = System.nanoTime();
    private final AtomicInteger eventSeq = new AtomicInteger();
    private final NodeProgressListener listener;
    private final ThreadLocal<Deque<ExecutionTrace.NodeTrace>> stack =
            ThreadLocal.withInitial(ArrayDeque::new);

    public TracingEngineObserver(IChainStore chainStore) {
        this(chainStore, NodeProgressListener.NOOP);
    }

    public TracingEngineObserver(IChainStore chainStore, NodeProgressListener listener) {
        this.chainStore = chainStore;
        this.listener = listener == null ? NodeProgressListener.NOOP : listener;
    }

    public ExecutionTrace trace() {
        return trace;
    }

    @Override
    public void onNodeStarted(NodeDelta delta) {
        RuleChain chain = chainStore.getChain(delta.chainId());
        String nodeId = delta.nodeId();
        ExecutionTrace.NodeTrace node = new ExecutionTrace.NodeTrace();
        node.setNodeId(nodeId);
        node.setNodeType(chain != null ? chain.nodeType(nodeId) : null);
        node.setStatus(ExecutionTrace.NODE_RUNNING);
        node.setStartedAtMs(offsetMs());
        node.setStartNanos(System.nanoTime());
        synchronized (trace) {
            node.setSeq(trace.getNodes().size() + 1);
            trace.getNodes().add(node);
            addEvent(ExecutionTrace.EVENT_START, nodeId, null, null, null);
        }
        stack.get().push(node);
        safe(() -> listener.onNodeStarted(nodeId, node.getNodeType()));
    }

    @Override
    public void onNodeFinished(NodeDelta delta) {
        String nodeId = delta.nodeId();
        long nanos = delta.nanos();
        ExecutionTrace.NodeTrace node = pop(nodeId);
        double durationMs;
        String inPayload = preview(delta.input());
        String outPayload = preview(delta.output());
        synchronized (trace) {
            if (node != null) {
                node.setStatus(ExecutionTrace.NODE_OK);
                node.setDurationMs(toMs(nanos));
                if (outPayload != null) {
                    node.setPayload(outPayload);
                }
            }
            durationMs = node == null ? toMs(nanos) : node.getDurationMs();
            addEvent(ExecutionTrace.EVENT_FINISH, nodeId, null, null,
                    node == null ? null : formatMs(node.getDurationMs()));
        }
        safe(() -> listener.onNodeFinished(nodeId, ExecutionTrace.NODE_OK, inPayload, outPayload, null, durationMs));
    }

    @Override
    public void onNodeError(String chainId, String nodeId, Object input, Throwable t) {
        ExecutionTrace.NodeTrace node = pop(nodeId);
        String message = describe(t);
        String inPayload = preview(input);
        double durationMs;
        synchronized (trace) {
            if (node != null) {
                node.setStatus(ExecutionTrace.NODE_ERROR);
                node.setError(message);
                node.setDurationMs(toMs(System.nanoTime() - node.getStartNanos()));
            }
            durationMs = node == null ? 0d : node.getDurationMs();
            addEvent(ExecutionTrace.EVENT_ERROR, nodeId, null, null, message);
        }

        final double cost = durationMs;
        safe(() -> listener.onNodeFinished(nodeId, ExecutionTrace.NODE_ERROR, inPayload, null, message, cost));
    }

    @Override
    public void onNodeRouted(NodeDelta delta) {
        String nodeId = delta.nodeId();
        Set<String> ports = delta.ports();
        List<String> targets = delta.targets();
        synchronized (trace) {
            ExecutionTrace.NodeTrace node = current(nodeId);
            if (node == null) {
                node = latest(nodeId);
            }
            if (node != null) {
                ExecutionTrace.Route route = new ExecutionTrace.Route();
                route.setPort(join(ports));
                route.setTargets(targets);
                node.getRoutes().add(route);
                String outPayload = preview(delta.output());
                if (outPayload != null) {
                    node.setPayload(outPayload);
                }
            }
            addEvent(ExecutionTrace.EVENT_ROUTE, nodeId, join(ports), targets, null);
        }
        safe(() -> listener.onNodeRouted(nodeId, targets));
    }

    @Override
    public void onPackTimeout(String queueName, int pendingCount) {
    }

    @Override
    public void onPackFailed(String queueName, int failedCount, List<String> samples) {
    }

    @Override
    public void onPackFinished(String queueName, int total, boolean committed) {
    }

    @Override
    public void onDeadLetter(String queueName, int reason, RuleMessage msg, Throwable error) {
    }

    /** 执行结束（含超时）：把未闭合的节点标记为中断，并补一条结束事件。 */
    public void finish(String status, String detail) {
        java.util.List<ExecutionTrace.NodeTrace> aborted = new java.util.ArrayList<ExecutionTrace.NodeTrace>();
        synchronized (trace) {
            for (ExecutionTrace.NodeTrace node : trace.getNodes()) {
                if (ExecutionTrace.NODE_RUNNING.equals(node.getStatus())) {
                    node.setStatus(ExecutionTrace.NODE_ABORTED);
                    node.setDurationMs(toMs(System.nanoTime() - node.getStartNanos()));
                    aborted.add(node);
                }
            }
            addEvent(ExecutionTrace.EVENT_END, null, null, null, detail != null ? detail : status);
        }
        for (ExecutionTrace.NodeTrace node : aborted) {
            final String nodeId = node.getNodeId();
            final String input = node.getInput();
            final double cost = node.getDurationMs();
            safe(() -> listener.onNodeFinished(nodeId, ExecutionTrace.NODE_ABORTED, input, null, null, cost));
        }
    }

    /** 安全执行监听回调：吞掉实现方异常，避免污染主执行链路。 */
    private void safe(Runnable action) {
        try {
            action.run();
        } catch (Exception ignored) {
            // 进度透出属旁路，任何异常都不应影响引擎执行
        }
    }

    private void addEvent(String type, String nodeId, String port, List<String> targets, String detail) {
        ExecutionTrace.TraceEvent event = new ExecutionTrace.TraceEvent();
        event.setSeq(eventSeq.incrementAndGet());
        event.setOffsetMs(offsetMs());
        event.setType(type);
        event.setNodeId(nodeId);
        event.setPort(port);
        event.setTargets(targets);
        event.setDetail(detail);
        trace.getEvents().add(event);
    }

    private ExecutionTrace.NodeTrace current(String nodeId) {
        Deque<ExecutionTrace.NodeTrace> deque = stack.get();
        for (ExecutionTrace.NodeTrace node : deque) {
            if (nodeId == null || nodeId.equals(node.getNodeId())) {
                return node;
            }
        }
        return null;
    }

    /**
     * 兜底归属：节点把决策放到异步线程后再 tellNext 时，当前线程栈是空的，
     * 但观测器拿到的 nodeId 依然可信，于是挂到最后一次已完成的同名节点上。
     */
    private ExecutionTrace.NodeTrace latest(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        List<ExecutionTrace.NodeTrace> nodes = trace.getNodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            ExecutionTrace.NodeTrace node = nodes.get(i);
            if (!nodeId.equals(node.getNodeId())) {
                continue;
            }
            if (ExecutionTrace.NODE_RUNNING.equals(node.getStatus())) {
                return null;
            }
            return node;
        }
        return null;
    }

    private ExecutionTrace.NodeTrace pop(String nodeId) {
        Deque<ExecutionTrace.NodeTrace> deque = stack.get();
        Iterator<ExecutionTrace.NodeTrace> it = deque.iterator();
        while (it.hasNext()) {
            ExecutionTrace.NodeTrace node = it.next();
            if (nodeId == null || nodeId.equals(node.getNodeId())) {
                it.remove();
                return node;
            }
        }
        return null;
    }

    private String preview(Object output) {
        if (output == null) {
            return null;
        }
        String s;
        try {
            s = output instanceof String ? (String) output : JsonUtil.toJsonString(output);
        } catch (Exception e) {
            s = String.valueOf(output);
        }
        return s.length() <= PAYLOAD_LIMIT ? s : s.substring(0, PAYLOAD_LIMIT) + "…";
    }

    private double offsetMs() {
        return toMs(System.nanoTime() - startNanos);
    }

    private double toMs(long nanos) {
        return Math.round(nanos / 1000d) / 1000d;
    }

    private static String formatMs(double ms) {
        return ms + "ms";
    }

    private static String join(Set<String> ports) {
        if (ports == null || ports.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String port : ports) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(port);
        }
        return sb.toString();
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "未知异常";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
