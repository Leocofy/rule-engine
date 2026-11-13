package io.fire.rule.spring.util;

import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.util.JsonUtil;
import io.fire.rule.spring.flowgram.workflow.value.ValueResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ValueResolver} 单元测试：ref / constant / template 三类 IFlowValue 对 {@link ExecutionState}
 * 的坍缩语义。随 ValueResolver 下沉 cmp（依赖 cmp 的 FlowValue）而从 core 的 ExecutionStateTest 拆出。
 *
 * @author Cai FeiYue
 */
class ValueResolverTest {

    @Test
    void collapsesRefConstantTemplate() {
        ExecutionState state = ExecutionState.start(jsonMap("{\"name\":\"foo\"}"), null)
                .withOutput("validate", jsonMap("{\"x\":1,\"arr\":[10,20]}"));

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("score", refFlowValue("validate", "x"));
        record.put("label", constantFlowValue("hello"));
        record.put("greeting", templateFlowValue("hi {{validate.arr.0}}"));

        Map<String, Object> resolved = ValueResolver.resolveValue(record, state);
        assertEquals(1, resolved.get("score"));
        assertEquals("hello", resolved.get("label"));
        assertEquals("hi 10", resolved.get("greeting"));
    }

    // ---------- helpers ----------

    private static Map<String, Object> jsonMap(String json) {
        return JsonUtil.parseObject(json);
    }

    private static Map<String, Object> refFlowValue(String scope, String... path) {
        List<Object> content = new ArrayList<>();
        content.add(scope);
        Collections.addAll(content, (Object[]) path);
        return flowValue("ref", content);
    }

    private static Map<String, Object> constantFlowValue(Object value) {
        return flowValue("constant", value);
    }

    private static Map<String, Object> templateFlowValue(String tpl) {
        return flowValue("template", tpl);
    }

    private static Map<String, Object> flowValue(String type, Object content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("content", content);
        return map;
    }
}
