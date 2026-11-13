package io.fire.rule.core.model;

import io.fire.rule.core.util.JsonUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行状态（统一 State 模型的核心）：一次链路执行里<b>唯一的可序列化数据状态快照</b>，
 * 沿节点投递逐跳流动，取代原先寄生在 {@code RuleMessage.getPayload()} 里的命名空间约定。
 *
 * <p>对应重构方案 A（不可变快照）：
 * <ul>
 *   <li><b>不可变</b>：所有 {@code withXxx} 派生方法返回<b>新实例</b>，原快照不变；
 *       写入采用结构共享浅拷贝（仅复制被改动的顶层 Map，其余引用复用）。</li>
 *   <li><b>分支隔离</b>：fan-out 时每个下游分支拿到各自的快照引用，并发分支互不串写。</li>
 *   <li><b>自包含</b>：内容全部为普通 Map/List/标量，{@link #toJson()}/{@link #fromJson(String)}
 *       无损往返，可随消息跨实例投递、进程重启后还原。</li>
 * </ul>
 *
 * <p>三段数据 + 一个作用域：
 * <ul>
 *   <li>{@code entry}：入口负载（原 payload 的非命名空间字段）；</li>
 *   <li>{@code outputs}：命名空间产出，{@code nodeId -> 该节点产出}（供下游 ref 取值）；</li>
 *   <li>{@code meta}：业务元数据（原 {@code MetaData} 并入）；</li>
 *   <li>{@code scope}：{@link ScopeFrame} 父链式作用域帧（全局变量 + 每层 Loop 局部量）。</li>
 * </ul>
 *
 * @author Cai FeiYue
 * @date 2026-11-05
 */
public final class ExecutionState {

    /** 空状态：三段皆空、空作用域帧。 */
    public static final ExecutionState EMPTY =
            new ExecutionState(Collections.<String, Object>emptyMap(),
                    Collections.<String, Object>emptyMap(),
                    Collections.<String, Object>emptyMap(),
                    ScopeFrame.EMPTY);

    private final Map<String, Object> entry;
    private final Map<String, Object> outputs;
    private final Map<String, Object> meta;
    private final ScopeFrame scope;

    private ExecutionState(Map<String, Object> entry, Map<String, Object> outputs,
                           Map<String, Object> meta, ScopeFrame scope) {
        this.entry = entry;
        this.outputs = outputs;
        this.meta = meta;
        this.scope = scope;
    }

    /**
     * 从入口负载构造初始状态。
     *
     * @param entry 入口负载字段（可为 {@code null}）
     * @param meta  初始元数据（可为 {@code null}）
     */
    public static ExecutionState start(Map<String, Object> entry, Map<String, Object> meta) {
        return new ExecutionState(Collections.unmodifiableMap(copy(entry)),
                Collections.emptyMap(),
                Collections.unmodifiableMap(copy(meta)), ScopeFrame.EMPTY);
    }

    // ---------- 派生（不可变 + 结构共享） ----------

    /**
     * 写入本节点产出，返回新快照：{@code outputs[nodeId] = output}。
     * 与旧 {@code NodeOutputs.write} 语义等价——POJO 会被规范成 JSON 原生结构供下游 ref 取值。
     */
    public ExecutionState withOutput(String nodeId, Object output) {
        Map<String, Object> next = new LinkedHashMap<>(outputs);
        next.put(nodeId, normalizeOutput(output));
        return new ExecutionState(entry, Collections.unmodifiableMap(next), meta, scope);
    }

    /** 替换入口负载，返回新快照（供 httpPull "用响应替换 payload" 这类改写使用）。 */
    public ExecutionState withEntry(Map<String, Object> newEntry) {
        return new ExecutionState(Collections.unmodifiableMap(copy(newEntry)), outputs, meta, scope);
    }

    /** 写入一个元数据键，返回新快照。 */
    public ExecutionState withMeta(String key, Object value) {
        Map<String, Object> next = new LinkedHashMap<String, Object>(meta);
        next.put(key, value);
        return new ExecutionState(entry, outputs, Collections.unmodifiableMap(next), scope);
    }

    /** 合并子链声明的产出到父链命名空间（D3：仅并回指定键，不外泄子链内部产出）。 */
    public ExecutionState mergeChildOutputs(Map<String, Object> declaredOutputs) {
        if (declaredOutputs == null || declaredOutputs.isEmpty()) {
            return this;
        }
        Map<String, Object> next = new LinkedHashMap<String, Object>(outputs);
        next.putAll(declaredOutputs);
        return new ExecutionState(entry, Collections.unmodifiableMap(next), meta, scope);
    }

    /** 压入一个新的作用域帧（进入 Loop 一轮 / 进入子链）。 */
    public ExecutionState pushFrame(String tag, Map<String, Object> vars) {
        return new ExecutionState(entry, outputs, meta, scope.child(tag, vars));
    }

    /** 弹出一个作用域帧（退出 Loop 一轮 / 子链回跳）。 */
    public ExecutionState popFrame() {
        return new ExecutionState(entry, outputs, meta, scope.pop());
    }

    // ---------- 读取 ----------

    public Map<String, Object> entry() {
        return entry;
    }

    public Map<String, Object> outputs() {
        return outputs;
    }

    public Map<String, Object> meta() {
        return meta;
    }

    public ScopeFrame scope() {
        return scope;
    }

    /** 取某节点的产出（无命名空间路径导航，整块返回）。 */
    public Object outputOf(String nodeId) {
        return outputs.get(nodeId);
    }

    /**
     * 命名空间取值：按点分路径（含数组下标）从 {@code outputs} 或 {@code entry} 深度取值。
     *
     * <p>解析顺序与旧 {@code NodeOutputs.read} 一致：先查产出命名空间 {@code outputs[scope]}，
     * 未命中再查入口负载字段 {@code entry[scope]}（兼容引用原始负载顶层字段）。
     *
     * @param scope 命名空间键（通常是产出节点 ID 或入口字段名）
     * @param path  点分路径，空串取整块
     */
    public Object read(String scope, String path) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        Object root = outputs.containsKey(scope) ? outputs.get(scope) : entry.get(scope);
        if (root == null) {
            return null;
        }
        if (path == null || path.isEmpty()) {
            return root;
        }
        return navigatePath(root, path);
    }

    /** 取元数据：命中且类型匹配才返回，否则 {@code null}（与旧 {@code MetaData#get} 语义一致）。 */
    @SuppressWarnings("unchecked")
    public <T> T metaValue(String key, Class<T> type) {
        Object obj = meta.get(key);
        return type.isInstance(obj) ? (T) obj : null;
    }

    // ---------- 序列化（自包含，随行投递） ----------

    /** JSON 化整份状态；作用域以"根→叶"帧列表承载。 */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("entry", entry);
        root.put("outputs", outputs);
        root.put("meta", meta);
        root.put("scope", flattenScope());
        return JsonUtil.toJsonString(root);
    }

    /** 从 JSON 还原状态；{@code null}/空串返回 {@link #EMPTY}。 */
    public static ExecutionState fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return EMPTY;
        }
        Map<String, Object> root = JsonUtil.parseObject(json);
        ScopeFrame scope = rebuildScope(root.get("scope"));
        return new ExecutionState(
                Collections.unmodifiableMap(copy(root.get("entry"))),
                Collections.unmodifiableMap(copy(root.get("outputs"))),
                Collections.unmodifiableMap(copy(root.get("meta"))),
                scope);
    }

    // ---------- 内部工具 ----------

    private List<Object> flattenScope() {
        List<Object> chain = new ArrayList<Object>();
        List<ScopeFrame> frames = new ArrayList<ScopeFrame>();
        ScopeFrame f = scope;
        while (f != null && f != ScopeFrame.EMPTY) {
            frames.add(0, f);
            f = f.getParent();
        }
        for (ScopeFrame fr : frames) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("tag", fr.getTag());
            item.put("vars", fr.vars());
            chain.add(item);
        }
        return chain;
    }

    private static ScopeFrame rebuildScope(Object raw) {
        List<Object> chain = JsonUtil.asArray(raw);
        ScopeFrame scope = ScopeFrame.EMPTY;
        for (Object o : chain) {
            Map<String, Object> item = JsonUtil.asObject(o);
            String tag = JsonUtil.asString(item.get("tag"));
            Map<String, Object> vars = JsonUtil.asObject(item.get("vars"));
            scope = scope == ScopeFrame.EMPTY
                    ? ScopeFrame.root(tag, vars)
                    : scope.child(tag, vars);
        }
        return scope;
    }

    /** POJO → JSON 原生结构（对齐旧 {@code NodeOutputs.write} 的产出规范化）。 */
    private static Object normalizeOutput(Object output) {
        if (output instanceof Map || output instanceof List || output == null) {
            return output;
        }
        try {
            return JsonUtil.asObject(output);
        } catch (Exception e) {
            return Collections.singletonMap("result", (Object) String.valueOf(output));
        }
    }

    /** 按点分路径（含数组下标）深度取值，语义与旧 {@code NodeOutputs.navigatePath} 一致。 */
    @SuppressWarnings("unchecked")
    static Object navigatePath(Object root, String path) {
        String[] segments = path.split("\\.");
        Object current = root;
        for (String segment : segments) {
            if (current == null) {
                return null;
            }
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(segment);
            } else if (current instanceof List) {
                try {
                    int index = Integer.parseInt(segment);
                    List<Object> list = (List<Object>) current;
                    current = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    private static Map<String, Object> copy(Map<String, Object> src) {
        return src == null ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(src);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Object src) {
        if (src == null) {
            return new LinkedHashMap<String, Object>();
        }
        if (src instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) src);
        }
        return JsonUtil.asObject(src);
    }
}
