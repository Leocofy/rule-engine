package io.fire.rule.expression;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.aviator.AviatorEvaluator;
import io.fire.rule.model.RuleMessage;
import io.fire.rule.nodes.SimpleExpressionEngine;
import io.fire.rule.spi.IExpressionEngine;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 基于 Aviator 的表达式引擎实现。
 *
 * <p>{@code evaluate} 使用 Aviator 执行条件表达式（编译为字节码，性能优于解释执行）；
 * {@code transform} 和 {@code route} 保持与 {@link SimpleExpressionEngine} 兼容的语法，
 * 规则链 JSON 无需修改即可切换引擎。
 *
 * <p>Aviator 表达式中可直接引用 payload 的 JSON 字段和 metadata 的键名，
 * 例如 {@code temp > 80}、{@code smoke == 1}、{@code level == 'critical'}。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
public class AviatorExpressionEngine implements IExpressionEngine {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleExpressionEngine delegate = new SimpleExpressionEngine();

    @Override
    public boolean evaluate(String expr, RuleMessage msg) {
        if (expr == null || expr.trim().isEmpty()) {
            return true;
        }
        try {
            Map<String, Object> env = buildEnv(msg);
            Object result = AviatorEvaluator.execute(expr, env);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            if (result instanceof Number) {
                return ((Number) result).doubleValue() != 0;
            }
            return result != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String transform(String expr, RuleMessage msg) {
        return delegate.transform(expr, msg);
    }

    @Override
    public Set<String> route(String expr, RuleMessage msg) {
        return delegate.route(expr, msg);
    }

    private Map<String, Object> buildEnv(RuleMessage msg) {
        Map<String, Object> env = new HashMap<>();
        String payload = msg.getPayload();
        if (payload != null && !payload.isEmpty() && payload.trim().startsWith("{")) {
            try {
                Map<String, Object> payloadMap = objectMapper.readValue(payload, new TypeReference<>() {
                });
                for (Map.Entry<String, Object> e : payloadMap.entrySet()) {
                    env.put(e.getKey(), unwrapNumber(e.getValue()));
                }
            } catch (Exception ignored) {
            }
        }
        for (Map.Entry<String, String> e : msg.getMetaData().values().entrySet()) {
            env.put(e.getKey(), unwrapNumber(e.getValue()));
        }
        return env;
    }

    private Object unwrapNumber(Object value) {
        if (value instanceof String) {
            String s = ((String) value).trim();
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e1) {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException e2) {
                    return value;
                }
            }
        }
        return value;
    }
}
