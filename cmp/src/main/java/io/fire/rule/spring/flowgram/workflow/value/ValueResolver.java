package io.fire.rule.spring.flowgram.workflow.value;

import io.fire.rule.core.config.IValueResolver;
import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.model.NodeConfig;
import io.fire.rule.core.spi.IExpressionEngine;
import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.core.util.JsonUtil;
import io.fire.rule.spring.flowgram.WorkflowConstants;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FlowGram 取值解析器：把 {@code Record<field, IFlowValue>} 逐字段坍缩成真实值。
 *
 * <p>统一 State 模型下取值根为 {@link ExecutionState}（{@code outputs}/{@code entry} 命名空间）。
 * 按类型解析：
 * <ul>
 *   <li>{@code constant} → content 原值；其中嵌套的 IFlowValue 叶子仍会被递归解析</li>
 *   <li>{@code ref} → {@link ExecutionState#read} 取命名空间值：{@code content[0]} 为 scope，余下拼成点分路径</li>
 *   <li>{@code expression} → {@link IExpressionEngine#value} 求值</li>
 *   <li>{@code template} → 对 {@code {{scope.path}}} 占位做取值插值</li>
 * </ul>
 *
 * @author rule-engine
 */
public final class ValueResolver implements IValueResolver {

    /**
     * 占位形如 {@code {{scope.a.b}}}，双花括号内为非空白内容。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)}}");

    /**
     * 解析单个值（IFlowValue）。非 IFlowValue 的裸值按 constant 处理。
     */
    public static Object resolveValue(Object raw, ExecutionState state, IExpressionEngine engine, INodeContext ctx) {
        return resolveValue(IFlowValue.from(raw), state, engine, ctx);
    }

    /**
     * 解析强模型中的 {@link IFlowValue}：按具体实现（constant/ref/expression/template）取值。
     */
    public static Object resolveValue(IFlowValue value, ExecutionState state, IExpressionEngine engine, INodeContext ctx) {
        if (value == null) {
            return null;
        }
        ExecutionState scope = state == null ? ExecutionState.EMPTY : state;
        if (value instanceof ConstantValue) {
            return resolveConstant(value.value(), scope, engine, ctx);
        }
        if (value instanceof RefValue) {
            List<String> path = ((RefValue) value).path();
            if (path == null || path.isEmpty()) {
                return null;
            }
            String scopeName = path.get(0);
            String fieldPath = path.size() == 1 ? "" : String.join(".", path.subList(1, path.size()));
            return ctx.withInput(fieldPath, () -> scope.read(scopeName, fieldPath));
        }
        if (value instanceof ExpressionValue) {
            return engine == null ? null : engine.value(((ExpressionValue) value).expression(), scope);
        }
        if (value instanceof TemplateValue) {
            return interpolate(((TemplateValue) value).template(), scope);
        }
        return value.value();
    }

    /**
     * constant：原值透传，但深走 Map/List，把内部嵌的 IFlowValue 叶子（如对象体内某字段是 ref）解析掉。
     */
    @SuppressWarnings("unchecked")
    private static Object resolveConstant(Object content, ExecutionState state, IExpressionEngine engine, INodeContext ctx) {
        if (content instanceof IFlowValue) {
            return resolveValue((IFlowValue) content, state, engine, ctx);
        }
        if (content instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) content;
            Map<String, Object> out = new LinkedHashMap<>(map.size());
            for (Map.Entry<String, Object> e : map.entrySet()) {
                out.put(e.getKey(), resolveLeafDeep(e.getValue(), state, engine, ctx));
            }
            return out;
        }
        if (content instanceof List) {
            List<Object> list = (List<Object>) content;
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(resolveLeafDeep(item, state, engine, ctx));
            }
            return out;
        }
        return content;
    }

    /**
     * 递归容器内的叶子：若某个子值本身是 IFlowValue 形状则解析，否则继续下探容器。
     */
    private static Object resolveLeafDeep(Object value, ExecutionState state, IExpressionEngine engine, INodeContext ctx) {
        if (value instanceof IFlowValue) {
            return resolveValue((IFlowValue) value, state, engine, ctx);
        }
        if (isFlowValueShape(value)) {
            return resolveValue(value, state, engine, ctx);
        }
        if (value instanceof Map || value instanceof List) {
            return resolveConstant(value, state, engine, ctx);
        }
        return value;
    }

    private static boolean isFlowValueShape(Object value) {
        if (!(value instanceof Map)) {
            return false;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        Object type = map.get("type");
        if (!(type instanceof String) || !map.containsKey("content")) {
            return false;
        }
        String name = (String) type;
        return "constant".equals(name) || "ref".equals(name)
                || "expression".equals(name) || "template".equals(name);
    }

    /**
     * 模板插值：整串恰为单个占位时返回原生对象，否则做字符串拼接替换。
     */
    private static Object interpolate(String template, ExecutionState state) {
        if (template == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        if (!m.find()) {
            return template;
        }
        m.reset();
        // 整串就是一个占位 → 返回解析后的原始类型值
        if (template.matches("^\\{\\{[^{}]+\\}\\}$")) {
            return lookup(m.group(1).trim(), state);
        }
        StringBuffer sb = new StringBuffer();
        m.reset();
        while (m.find()) {
            Object v = lookup(m.group(1).trim(), state);
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 占位内容 {@code scope.path} → 命名空间取值。
     */
    private static Object lookup(String expr, ExecutionState state) {
        int dot = expr.indexOf('.');
        String scope = dot < 0 ? expr : expr.substring(0, dot);
        String path = dot < 0 ? "" : expr.substring(dot + 1);
        return state.read(scope, path);
    }

    @Override
    public Object resolveOne(Object raw, INodeContext ctx, IExpressionEngine engine) {
        return resolveValue(raw, ctx.state(), engine == null ? ctx.expressionEngine() : engine, ctx);
    }

}
