package io.fire.rule.core.cluster;

import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.model.RuleMessage;
import io.fire.rule.core.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一 State 模型 Step 4 序列化随行测试：验证 {@link JsonMessageSerializer} 把
 * {@link RuleMessage} 信封 + {@link ExecutionState} 快照编解码后<b>无损还原</b>，
 * 且分区键（originator）与路由指令（chainId / nodeId / relations）随行不变。
 *
 * @author Cai FeiYue
 */
class JsonMessageSerializerTest {

    private final JsonMessageSerializer serializer = new JsonMessageSerializer();

    /** 入口负载 + 命名空间产出 + 元数据 + 作用域整份往返无损。 */
    @Test
    void stateRoundTripIsLossless() {
        ExecutionState state = ExecutionState
                .start(map("{\"deviceId\":\"d1\",\"temperature\":88}"), map("{\"traceId\":\"t-9\"}"))
                .withOutput("validate", map("{\"ok\":true,\"tags\":[\"a\",\"b\"]}"))
                .withMeta("riskLevel", "HIGH")
                .withEntry(map("{\"deviceId\":\"d1\",\"risk\":{\"score\":91}}"))
                .pushFrame("loop1", map("{\"index\":\"2\"}"));

        RuleMessage msg = RuleMessage.newMessage()
                .id("fixed-id")
                .type("TELEMETRY")
                .originator("d1")
                .state(state)
                .build();

        byte[] bytes = serializer.serialize(msg, "fireAlarm", "riskEnrich", set("Success", "Failure"));
        TransportMessage t = serializer.deserialize(bytes);
        RuleMessage restored = serializer.toRuleMessage(t);

        // 数据状态无损：整份 JSON 相等即证明 entry/outputs/meta/scope 全部还原
        assertEquals(state.toJson(), restored.getState().toJson(), "state 应逐字节无损往返");
        assertEquals(91, restored.getState().read("risk", "score"), "替换后的入口负载应可按路径取值");
        assertEquals("HIGH", restored.getState().metaValue("riskLevel", String.class));
        assertEquals("2", restored.getState().scope().lookup("index"), "作用域变量应可回溯");

        // 信封字段随行不变
        assertEquals("fixed-id", restored.getId());
        assertEquals("TELEMETRY", restored.getType());
        assertEquals("d1", restored.getOriginator(), "分区键必须留在信封并随行");

        // 路由指令随行
        assertEquals("fireAlarm", t.getChainId());
        assertEquals("riskEnrich", t.getNodeId());
        assertEquals(set("Success", "Failure"), t.getRelations());
    }

    /** 两个分支各自快照独立序列化，往返后仍互不串写（分支隔离随行保持）。 */
    @Test
    void branchIsolationSurvivesRoundTrip() {
        ExecutionState base = ExecutionState.start(map("{\"k\":\"v\"}"), null);
        ExecutionState branchA = base.withOutput("a", map("{\"x\":1}"));
        ExecutionState branchB = base.withOutput("b", map("{\"y\":2}"));

        RuleMessage msgA = RuleMessage.newMessage().originator("d").state(branchA).build();
        RuleMessage msgB = RuleMessage.newMessage().originator("d").state(branchB).build();

        RuleMessage rA = serializer.toRuleMessage(
                serializer.deserialize(serializer.serialize(msgA, "c", "n", null)));
        RuleMessage rB = serializer.toRuleMessage(
                serializer.deserialize(serializer.serialize(msgB, "c", "n", null)));

        assertTrue(rA.getState().outputs().containsKey("a"));
        assertNull(rA.getState().outputOf("b"), "分支 A 往返后不应混入分支 B 的产出");
        assertTrue(rB.getState().outputs().containsKey("b"));
        assertNull(rB.getState().outputOf("a"), "分支 B 往返后不应混入分支 A 的产出");
    }

    /** relations 为空时不应写出该字段，反序列化后为空集。 */
    @Test
    void emptyRelationsRoundTrip() {
        RuleMessage msg = RuleMessage.newMessage().originator("d").payload("{}").build();
        TransportMessage t = serializer.deserialize(serializer.serialize(msg, "c", "n", null));
        assertTrue(t.getRelations().isEmpty(), "无关系时应还原为空集");
    }

    // ---------- helpers ----------

    private static Map<String, Object> map(String json) {
        return JsonUtil.parseObject(json);
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
