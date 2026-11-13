package io.fire.rule.spring.flowgram.util;

import io.fire.rule.core.util.JsonUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Description: JsonSchemaValidator —— 零外部依赖的 JSON Schema 子集校验器。
 *
 * <p>只为节点参数这类"结构可控的配置"服务，覆盖常用关键字，不追求规范全量：
 * type / enum / const / required / properties / additionalProperties / items /
 * minItems / maxItems / minimum / maximum / exclusiveMinimum / exclusiveMaximum /
 * minLength / maxLength / pattern / allOf / anyOf / oneOf / if-then-else。
 *
 * <p>输入是已解析成 Java 对象的 JSON（JsonUtil.parseAny）与 schema（{@link JsonUtil#parseObject}）；
 * 布尔形式的全通配/全拒绝 schema（true / false）也支持。错误以 JSON Pointer 风格的路径回填。
 *
 * @author Cai FeiYue
 * @date 2026/10/28
 */
public final class JsonSchemaValidator {

    private JsonSchemaValidator() {
    }

    /**
     * 校验实例是否满足 schema。
     *
     * @param schema   已解析的 schema（Map / Boolean）
     * @param instance 已解析的待校验值
     * @return 错误描述列表，空表示通过
     */
    public static List<String> validate(Object schema, Object instance) {
        List<String> errors = new ArrayList<>();
        validateNode(schema, instance, "", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private static void validateNode(Object schemaNode, Object instance, String path, List<String> errors) {
        // 布尔 schema：true 放行，false 拒绝
        if (schemaNode instanceof Boolean) {
            if (Boolean.FALSE.equals(schemaNode)) {
                errors.add(where(path) + "不允许该取值");
            }
            return;
        }
        if (!(schemaNode instanceof Map)) {
            return;
        }
        Map<String, Object> schema = (Map<String, Object>) schemaNode;

        checkType(schema, instance, path, errors);
        checkEnumConst(schema, instance, path, errors);
        checkCombinators(schema, instance, path, errors);
        checkIfThenElse(schema, instance, path, errors);
        checkString(schema, instance, path, errors);
        checkNumber(schema, instance, path, errors);

        if (instance instanceof Map) {
            checkObject(schema, (Map<String, Object>) instance, path, errors);
        } else if (instance instanceof Collection) {
            checkArray(schema, (Collection<Object>) instance, path, errors);
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkType(Map<String, Object> schema, Object instance, String path, List<String> errors) {
        Object typeNode = schema.get("type");
        if (typeNode == null) {
            return;
        }
        List<String> allowed = new ArrayList<>();
        if (typeNode instanceof Collection) {
            for (Object item : (Collection<Object>) typeNode) {
                allowed.add(String.valueOf(item));
            }
        } else {
            allowed.add(String.valueOf(typeNode));
        }
        for (String type : allowed) {
            if (matchesType(type, instance)) {
                return;
            }
        }
        errors.add(where(path) + "类型应为 " + allowed + "，实际是 " + typeName(instance));
    }

    private static boolean matchesType(String type, Object instance) {
        switch (type) {
            case "object":
                return instance instanceof Map;
            case "array":
                return instance instanceof Collection;
            case "string":
                return instance instanceof String;
            case "boolean":
                return instance instanceof Boolean;
            case "null":
                return instance == null;
            case "number":
                return instance instanceof Number;
            case "integer":
                return instance instanceof Number && isIntegral((Number) instance);
            default:
                return true;
        }
    }

    private static boolean isIntegral(Number number) {
        double d = number.doubleValue();
        return d == Math.floor(d) && !Double.isInfinite(d);
    }

    @SuppressWarnings("unchecked")
    private static void checkEnumConst(Map<String, Object> schema, Object instance, String path, List<String> errors) {
        Object enumNode = schema.get("enum");
        if (enumNode instanceof Collection) {
            Collection<Object> options = (Collection<Object>) enumNode;
            boolean hit = false;
            for (Object option : options) {
                if (deepEquals(option, instance)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                errors.add(where(path) + "只能取 " + options + "，实际是 " + instance);
            }
        }
        if (schema.containsKey("const")) {
            if (!deepEquals(schema.get("const"), instance)) {
                errors.add(where(path) + "必须等于 " + schema.get("const") + "，实际是 " + instance);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkCombinators(Map<String, Object> schema, Object instance, String path, List<String> errors) {
        Object allOf = schema.get("allOf");
        if (allOf instanceof Collection) {
            for (Object sub : (Collection<Object>) allOf) {
                validateNode(sub, instance, path, errors);
            }
        }
        Object anyOf = schema.get("anyOf");
        if (anyOf instanceof Collection) {
            Collection<Object> subs = (Collection<Object>) anyOf;
            if (!subs.isEmpty() && !anyPasses(subs, instance)) {
                errors.add(where(path) + "至少需满足 anyOf 中的一个子约束");
            }
        }
        Object oneOf = schema.get("oneOf");
        if (oneOf instanceof Collection) {
            Collection<Object> subs = (Collection<Object>) oneOf;
            int passed = 0;
            for (Object sub : subs) {
                if (noErrors(sub, instance)) {
                    passed++;
                }
            }
            if (passed != 1) {
                errors.add(where(path) + "需恰好满足 oneOf 中的一个子约束，实际满足 " + passed + " 个");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkIfThenElse(Map<String, Object> schema, Object instance, String path, List<String> errors) {
        if (!schema.containsKey("if")) {
            return;
        }
        boolean matched = noErrors(schema.get("if"), instance);
        if (matched && schema.containsKey("then")) {
            validateNode(schema.get("then"), instance, path, errors);
        } else if (!matched && schema.containsKey("else")) {
            validateNode(schema.get("else"), instance, path, errors);
        }
    }

    private static void checkString(Map<String, Object> schema, Object instance, String path, List<String> errors) {
        if (!(instance instanceof String)) {
            return;
        }
        String text = (String) instance;
        Integer minLength = asInt(schema.get("minLength"));
        if (minLength != null && text.length() < minLength) {
            errors.add(where(path) + "长度不能小于 " + minLength);
        }
        Integer maxLength = asInt(schema.get("maxLength"));
        if (maxLength != null && text.length() > maxLength) {
            errors.add(where(path) + "长度不能大于 " + maxLength);
        }
        Object pattern = schema.get("pattern");
        if (pattern != null) {
            try {
                if (!Pattern.compile(String.valueOf(pattern)).matcher(text).find()) {
                    errors.add(where(path) + "不匹配模式 " + pattern);
                }
            } catch (PatternSyntaxException e) {
                errors.add(where(path) + "schema 的 pattern 非法: " + pattern);
            }
        }
    }

    private static void checkNumber(Map<String, Object> schema, Object instance, String path, List<String> errors) {
        if (!(instance instanceof Number)) {
            return;
        }
        double d = ((Number) instance).doubleValue();
        checkBound(schema, "minimum", d, path, errors, true);
        checkBound(schema, "maximum", d, path, errors, false);
        checkBound(schema, "exclusiveMinimum", d, path, errors, true);
        checkBound(schema, "exclusiveMaximum", d, path, errors, false);
    }

    private static void checkBound(Map<String, Object> schema, String keyword, double value,
                                   String path, List<String> errors, boolean lower) {
        Object node = schema.get(keyword);
        if (!(node instanceof Number)) {
            return;
        }
        double bound = ((Number) node).doubleValue();
        boolean inclusive = "minimum".equals(keyword) || "maximum".equals(keyword);
        boolean ok = lower
                ? (inclusive ? value >= bound : value > bound)
                : (inclusive ? value <= bound : value < bound);
        if (!ok) {
            String cmp = lower ? (inclusive ? "不能小于 " : "必须大于 ") : (inclusive ? "不能大于 " : "必须小于 ");
            errors.add(where(path) + cmp + plain(bound));
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkObject(Map<String, Object> schema, Map<String, Object> instance, String path, List<String> errors) {
        Object requiredNode = schema.get("required");
        if (requiredNode instanceof Collection) {
            for (Object item : (Collection<Object>) requiredNode) {
                String field = String.valueOf(item);
                if (!instance.containsKey(field)) {
                    errors.add(where(path + "/" + field) + "为必填字段");
                }
            }
        }
        Map<String, Object> properties = asMap(schema.get("properties"));
        Object additional = schema.get("additionalProperties");
        for (Map.Entry<String, Object> entry : instance.entrySet()) {
            String field = entry.getKey();
            boolean declared = properties != null && properties.containsKey(field);
            if (declared) {
                validateNode(properties.get(field), entry.getValue(), path + "/" + field, errors);
            } else if (additional instanceof Boolean) {
                if (Boolean.FALSE.equals(additional)) {
                    errors.add(where(path + "/" + field) + "是未允许的属性");
                }
            } else if (additional != null && !(additional instanceof Boolean)) {
                validateNode(additional, entry.getValue(), path + "/" + field, errors);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkArray(Map<String, Object> schema, Collection<Object> instance, String path, List<String> errors) {
        int size = instance.size();
        Integer minItems = asInt(schema.get("minItems"));
        if (minItems != null && size < minItems) {
            errors.add(where(path) + "元素个数不能少于 " + minItems);
        }
        Integer maxItems = asInt(schema.get("maxItems"));
        if (maxItems != null && size > maxItems) {
            errors.add(where(path) + "元素个数不能超过 " + maxItems);
        }
        Object items = schema.get("items");
        if (items != null && !(items instanceof Boolean && (Boolean) items)) {
            int index = 0;
            for (Object element : instance) {
                validateNode(items, element, path + "/" + index, errors);
                index++;
            }
        }
    }

    private static boolean anyPasses(Collection<Object> subs, Object instance) {
        for (Object sub : subs) {
            if (noErrors(sub, instance)) {
                return true;
            }
        }
        return false;
    }

    private static boolean noErrors(Object sub, Object instance) {
        List<String> temp = new ArrayList<>();
        validateNode(sub, instance, "", temp);
        return temp.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static boolean deepEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        if (a instanceof Map && b instanceof Map) {
            Map<String, Object> ma = (Map<String, Object>) a;
            Map<String, Object> mb = (Map<String, Object>) b;
            if (ma.size() != mb.size()) {
                return false;
            }
            for (Map.Entry<String, Object> entry : ma.entrySet()) {
                if (!mb.containsKey(entry.getKey()) || !deepEquals(entry.getValue(), mb.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Collection && b instanceof Collection) {
            List<Object> la = new ArrayList<>((Collection<Object>) a);
            List<Object> lb = new ArrayList<>((Collection<Object>) b);
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!deepEquals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map ? (Map<String, Object>) node : null;
    }

    private static Integer asInt(Object node) {
        return node instanceof Number ? ((Number) node).intValue() : null;
    }

    private static String where(String path) {
        return (path == null || path.isEmpty()) ? "根节点" : "字段 " + path;
    }

    private static String typeName(Object instance) {
        if (instance == null) {
            return "null";
        }
        if (instance instanceof Map) {
            return "object";
        }
        if (instance instanceof Collection) {
            return "array";
        }
        if (instance instanceof String) {
            return "string";
        }
        if (instance instanceof Boolean) {
            return "boolean";
        }
        if (instance instanceof Number) {
            return "number";
        }
        return instance.getClass().getSimpleName();
    }

    private static String plain(double v) {
        return v == Math.floor(v) && !Double.isInfinite(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
