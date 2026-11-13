package io.fire.rule.core.model;

import io.fire.rule.core.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一 State 模型 Step 1 单元测试：验证 {@link ExecutionState} / {@link ScopeFrame} 的
 * 不可变性、分支隔离、序列化随行、以及 ref 取值与既有命名空间语义等价。
 *
 * @author Cai FeiYue
 */
public class ExecutionStateTest {

    /** ① withOutput 产新版且原快照不变；② 两分支各 withOutput 后互不可见（隔离）。 */
    @Test
    void withOutputIsImmutableAndBranchIsolated() {
        ExecutionState base = ExecutionState.start(jsonMap("{\"deviceId\":\"d1\"}"), null);

        ExecutionState branchA = base.withOutput("validate", jsonMap("{\"ok\":true}"));
        ExecutionState branchB = base.withOutput("enrich", jsonMap("{\"risk\":\"high\"}"));

        // 原快照不含任何产出
        assertTrue(base.outputs().isEmpty(), "原快照不应被写入");

        // 各分支只见自己的产出，互不可见
        assertSame(branchA, branchA);
        assertNotNullOutput(branchA, "validate");
        assertNull(branchA.outputOf("enrich"), "分支 A 不应看到分支 B 的产出");
        assertNotNullOutput(branchB, "enrich");
        assertNull(branchB.outputOf("validate"), "分支 B 不应看到分支 A 的产出");

        // 派生实例是不同对象
        assertNotSame(branchA, base);
        assertNotSame(branchA, branchB);
    }

    /** ③ toJson/fromJson round-trip 无损（entry / outputs / meta / scope）。 */
    @Test
    void jsonRoundTripIsLossless() {
        ExecutionState state = ExecutionState
                .start(jsonMap("{\"name\":\"foo\",\"amount\":10}"), jsonMap("{\"traceId\":\"t-1\"}"))
                .withOutput("validate", jsonMap("{\"x\":1,\"arr\":[10,20]}"))
                .withMeta("retry", "2")
                .pushFrame("loop1", jsonMap("{\"index\":\"0\"}"));

        ExecutionState restored = ExecutionState.fromJson(state.toJson());

        assertEquals(state.entry(), restored.entry(), "entry 应无损还原");
        assertEquals(state.outputs(), restored.outputs(), "outputs 应无损还原");
        assertEquals(state.meta(), restored.meta(), "meta 应无损还原");
        assertEquals(state.scope().depth(), restored.scope().depth(), "作用域深度应一致");
        assertEquals("0", restored.scope().lookup("index"), "作用域变量应可回溯");
        assertEquals("t-1", restored.metaValue("traceId", String.class));

        // 空态还原
        assertSame(ExecutionState.EMPTY, ExecutionState.fromJson(null));
    }

    /** ④ ref 取值（{@link ExecutionState#read}）与既有命名空间语义一致。 */
    @Test
    void refResolutionSemantics() {
        // 新路径：ExecutionState.read（outputs 未命中的 scope 回退到 entry，兼容原始字段）
        ExecutionState state = ExecutionState.start(jsonMap("{\"name\":\"foo\"}"), null)
                .withOutput("validate", jsonMap("{\"x\":1,\"arr\":[10,20]}"));
        assertEquals(jsonMap("{\"x\":1,\"arr\":[10,20]}"), state.read("validate", ""));
        assertEquals(1, state.read("validate", "x"));
        assertEquals(10, state.read("validate", "arr.0"));
        assertEquals("foo", state.read("name", ""), "原始入口字段 ref 也应等价");
    }

    /** ScopeFrame 父链回溯与弹出。 */
    @Test
    void scopeFrameLookupAndPop() {
        ScopeFrame root = ScopeFrame.root(jsonMap("{\"g\":\"gv\"}"));
        ScopeFrame child = root.child("loop1", jsonMap("{\"g\":\"local\",\"i\":\"3\"}"));

        assertEquals("local", child.lookup("g"), "就近优先应命中子帧");
        assertEquals("3", child.lookup("i"));
        assertEquals("gv", child.pop().lookup("g"), "弹出后回到父帧");
        assertNull(ScopeFrame.EMPTY.lookup("g"));
        assertFalse(root == ScopeFrame.EMPTY);
    }

    // ---------- helpers ----------

    private static void assertNotNullOutput(ExecutionState state, String nodeId) {
        assertTrue(state.outputs().containsKey(nodeId), "应包含产出 " + nodeId);
    }

    private static Map<String, Object> jsonMap(String json) {
        return JsonUtil.parseObject(json);
    }

    // Test scenario: start(entry, meta) 必须返回只读视图，且对外部源 Map 做防御性拷贝。
    @Test
    void startExposesReadOnlyViewsAndCopiesSource() {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("device", "d1");
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("traceId", "t1");

        ExecutionState state = ExecutionState.start(entry, meta);

        assertEquals("d1", state.entry().get("device"));
        assertEquals("t1", state.meta().get("traceId"));

        assertThrows(UnsupportedOperationException.class, () -> state.entry().put("x", 1),
                "entry 必须是只读视图");
        assertThrows(UnsupportedOperationException.class, () -> state.entry().remove("device"),
                "entry 必须禁止删除");
        assertThrows(UnsupportedOperationException.class, () -> state.meta().put("x", 1),
                "meta 必须是只读视图");
        assertThrows(UnsupportedOperationException.class, () -> state.outputs().put("x", 1),
                "outputs 必须是只读视图");

        // 构造后修改源 Map 不得回写快照
        entry.put("device", "changed");
        entry.put("added", "v");
        meta.put("traceId", "changed");
        assertEquals("d1", state.entry().get("device"), "start 应拷贝入口负载而非持有引用");
        assertNull(state.entry().get("added"), "后续新增键不得出现在快照中");
        assertEquals("t1", state.metaValue("traceId", String.class), "start 应拷贝元数据而非持有引用");
    }
}
