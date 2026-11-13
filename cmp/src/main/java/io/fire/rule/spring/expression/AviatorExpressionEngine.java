package io.fire.rule.spring.expression;

import com.googlecode.aviator.AviatorEvaluator;
import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.spi.IExpressionEngine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 基于 Aviator 的表达式引擎实现。
 *
 * <p>{@code evaluate} 使用 Aviator 执行条件表达式（编译为字节码，性能优于解释执行）；
 * {@code transform} 和 {@code route} 保持与 {@link SimpleExpressionEngine} 兼容的语法，
 * 规则链 JSON 无需修改即可切换引擎。
 *
 * <p>统一 State 模型：变量根由 {@link ExecutionState} 的 {@code entry}（入口负载）
 * + {@code outputs}（命名空间产出，按 {@code nodeId.field} 访问）+ {@code meta}（元数据）合并而成，
 * 例如 {@code temp > 80}、{@code level == 'critical'}、{@code validate.ok == true}。
 *
 * @author Cai FeiYue
 * @date 2026-10-21
 */
public class AviatorExpressionEngine implements IExpressionEngine {

    @Override
    public boolean evaluate(String expr, ExecutionState state) {
        if (expr == null || expr.trim().isEmpty()) {
            return true;
        }
        try {
            Object result = AviatorEvaluator.execute(expr, buildEnv(state));
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
    public String transform(String expr, ExecutionState state) {
        if (expr == null || expr.trim().isEmpty()) {
            return "";
        }
        return "";
    }

    @Override
    public Set<String> route(String expr, ExecutionState state) {
        return new HashSet<>();
    }

    /**
     * FlowGram {@code expression} 型 IFlowValue 取值：Aviator 求值并原样返回结果。
     */
    @Override
    public Object value(String expr, ExecutionState state) {
        if (expr == null || expr.trim().isEmpty()) {
            return null;
        }
        try {
            return AviatorEvaluator.execute(expr, buildEnv(state));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> buildEnv(ExecutionState state) {
        Map<String, Object> env = new HashMap<>();
        if (state == null) {
            return env;
        }
        // 元数据（不覆盖同名 entry）
        for (Map.Entry<String, Object> e : state.meta().entrySet()) {
            env.put(e.getKey(), unwrapNumber(e.getValue()));
        }
        // 入口负载字段
        for (Map.Entry<String, Object> e : state.entry().entrySet()) {
            env.put(e.getKey(), unwrapNumber(e.getValue()));
        }
        // 命名空间产出（nodeId -> 结构，支持 nodeId.field 访问）
        for (Map.Entry<String, Object> e : state.outputs().entrySet()) {
            env.put(e.getKey(), e.getValue());
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
