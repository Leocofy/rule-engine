package io.fire.rule.core.observability;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 单跳增量（观测层，对应重构 D4）：观测钩子只携带<b>本跳 delta</b>，而非整份 {@code ExecutionState}。
 *
 * <p>三种时点：
 * <ul>
 *   <li>{@link Kind#STARTED}：节点即将执行，带进入态输入（节点实际解析后的 inputs）。</li>
 *   <li>{@link Kind#ROUTED}：节点完成路由，带本跳产出 + 端口 + 下游目标。</li>
 *   <li>{@link Kind#FINISHED}：节点执行结束，带本跳产出 + 耗时。</li>
 * </ul>
 *
 * <p>{@code input} / {@code output} 是否截断/脱敏由观测实现决定，核心不做预览裁剪。
 *
 * @author Cai FeiYue
 * @date 2026-11-05
 */
public final class NodeDelta {

    public enum Kind {STARTED, ROUTED, FINISHED}

    private final Kind kind;
    private final String chainId;
    private final String nodeId;
    private final Object input;
    private final Object output;
    private final Set<String> ports;
    private final List<String> targets;
    private final long nanos;

    private NodeDelta(Kind kind, String chainId, String nodeId, Object input, Object output,
                      Set<String> ports, List<String> targets, long nanos) {
        this.kind = kind;
        this.chainId = chainId;
        this.nodeId = nodeId;
        this.input = input;
        this.output = output;
        this.ports = ports == null ? Collections.<String>emptySet() : ports;
        this.targets = targets == null ? Collections.<String>emptyList() : targets;
        this.nanos = nanos;
    }

    public static NodeDelta started(String chainId, String nodeId) {
        return started(chainId, nodeId, null);
    }

    /**
     * 节点开始执行，携带进入态输入（供试跑/调试展示节点 inputs）。
     */
    public static NodeDelta started(String chainId, String nodeId, Object input) {
        return new NodeDelta(Kind.STARTED, chainId, nodeId, input, null, null, null, 0L);
    }

    public static NodeDelta routed(String chainId, String nodeId, Object output,
                                   Set<String> ports, List<String> targets) {
        return new NodeDelta(Kind.ROUTED, chainId, nodeId, null, output, ports, targets, 0L);
    }

    public static NodeDelta finished(String chainId, String nodeId, Object input, Object output, long nanos) {
        return new NodeDelta(Kind.FINISHED, chainId, nodeId, input, output, null, null, nanos);
    }

    public Kind kind() {
        return kind;
    }

    public String chainId() {
        return chainId;
    }

    public String nodeId() {
        return nodeId;
    }

    /**
     * 进入态输入；仅 STARTED 有值，其余为 null。
     */
    public Object input() {
        return input;
    }

    public Object output() {
        return output;
    }

    public Set<String> ports() {
        return ports;
    }

    public List<String> targets() {
        return targets;
    }

    public long nanos() {
        return nanos;
    }
}