package io.fire.rule.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 作用域帧：不可变、父链式的变量作用域，供统一 State 模型的 Loop / 嵌套链变量隔离使用。
 *
 * <p>设计要点（对应重构方案 A · 不可变快照）：
 * <ul>
 *   <li><b>只读</b>：一旦构造其 {@code vars} 与 {@code parent} 引用不再变化；
 *       进入一轮迭代时通过 {@link #child} 派生新帧，父帧保持原样。</li>
 *   <li><b>结构共享</b>：子帧只保存对父帧的引用与本轮新增变量，
 *       不复制整条链，成本与新增量成正比。</li>
 *   <li><b>可序列化</b>：帧内容全部为普通 {@code Map}/{@code List}/标量，
 *       可随 {@link ExecutionState} 一起 JSON 化、跨实例投递后还原。</li>
 * </ul>
 *
 * <p>典型形态：根帧承载全局变量（{@code globalVariable}），
 * Loop 每轮压入子帧承载 {@code {item, index}} 等局部量；
 * 读取按"就近优先"自内向外回溯。
 *
 * @author Cai FeiYue
 * @date 2026-11-05
 */
public final class ScopeFrame {

    /** 空帧：无任何变量、无父帧。 */
    public static final ScopeFrame EMPTY = new ScopeFrame(null, Collections.<String, Object>emptyMap(), null);

    private final ScopeFrame parent;
    private final Map<String, Object> vars;
    /** 帧标签，通常是对应的 Loop / 子链 ID，便于调试与定向回溯；可为空。 */
    private final String tag;

    private ScopeFrame(ScopeFrame parent, Map<String, Object> vars, String tag) {
        this.parent = parent;
        this.vars = vars;
        this.tag = tag;
    }

    /** 以给定变量构造一个根帧。 */
    public static ScopeFrame root(Map<String, Object> vars) {
        return new ScopeFrame(null, immutable(vars), null);
    }

    /** 以给定标签 + 变量构造一个根帧（供 {@code pushFrame} 还原时保留根帧 tag，保证 JSON 往返无损）。 */
    public static ScopeFrame root(String tag, Map<String, Object> vars) {
        return new ScopeFrame(null, immutable(vars), tag);
    }

    /**
     * 派生子帧：把本轮局部变量压入新帧，父帧指向当前帧。原帧不受影响（不可变）。
     *
     * @param tag  子帧标签（Loop / 子链 ID），可为 {@code null}
     * @param vars 本轮局部变量，可为 {@code null}
     * @return 新子帧
     */
    public ScopeFrame child(String tag, Map<String, Object> vars) {
        return new ScopeFrame(this, immutable(vars), tag);
    }

    /**
     * 按"就近优先"回溯查找：本帧命中即返回，否则查父帧，直到根帧或 null。
     *
     * @param key 变量名
     * @return 命中值；不存在返回 {@code null}
     */
    public Object lookup(String key) {
        ScopeFrame frame = this;
        while (frame != null) {
            if (frame.vars.containsKey(key)) {
                return frame.vars.get(key);
            }
            frame = frame.parent;
        }
        return null;
    }

    /** 本帧（不含父帧）是否直接持有该变量。 */
    public boolean holds(String key) {
        return vars.containsKey(key);
    }

    /** 回到父帧；已是根帧返回 {@link #EMPTY}。 */
    public ScopeFrame pop() {
        return parent == null ? EMPTY : parent;
    }

    public ScopeFrame getParent() {
        return parent;
    }

    public String getTag() {
        return tag;
    }

    /** 本帧变量的只读视图。 */
    public Map<String, Object> vars() {
        return vars;
    }

    /** 深度（根帧为 1，空帧为 0）。 */
    public int depth() {
        int d = 0;
        ScopeFrame frame = this;
        while (frame != null && frame != EMPTY) {
            d++;
            frame = frame.parent;
        }
        return d;
    }

    private static Map<String, Object> immutable(Map<String, Object> src) {
        if (src == null || src.isEmpty()) {
            return Collections.<String, Object>emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(src));
    }
}
