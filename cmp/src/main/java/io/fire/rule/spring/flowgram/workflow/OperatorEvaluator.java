package io.fire.rule.spring.flowgram.workflow;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * FlowGram Condition 节点的算子求值器：作用于已解析的 left / right 值，不依赖表达式引擎。
 *
 * <p>算子集与 FlowGram {@code ConditionOperator} 枚举对齐：
 * {@code eq / neq / gt / gte / lt / lte / in / nin / contains / not_contains /
 * is_empty / is_not_empty / is_true / is_false}；
 * 另兼容历史命名 {@code includes}（=contains）、{@code startsWith}、{@code endsWith}。
 * 数值比较走 {@code double}，其余退化为字符串与对象语义。
 *
 * @author rule-engine
 */
public final class OperatorEvaluator {

    public static final String EQ = "eq";
    public static final String NEQ = "neq";
    public static final String GT = "gt";
    public static final String GTE = "gte";
    public static final String LT = "lt";
    public static final String LTE = "lte";
    public static final String INCLUDES = "includes";
    public static final String STARTS_WITH = "startsWith";
    public static final String ENDS_WITH = "endsWith";
    // —— 与 FlowGram ConditionOperator 对齐的集合 / 包含 / 空值 / 布尔算子 ——
    public static final String CONTAINS = "contains";
    public static final String NOT_CONTAINS = "not_contains";
    public static final String IN = "in";
    public static final String NIN = "nin";
    public static final String IS_EMPTY = "is_empty";
    public static final String IS_NOT_EMPTY = "is_not_empty";
    public static final String IS_TRUE = "is_true";
    public static final String IS_FALSE = "is_false";

    private OperatorEvaluator() {
    }

    public static boolean test(String operator, Object left, Object right) {
        if (operator == null) {
            return false;
        }
        switch (operator) {
            case EQ:
                return equals(left, right);
            case NEQ:
                return !equals(left, right);
            case GT:
                return compare(left, right) > 0;
            case GTE:
                return compare(left, right) >= 0;
            case LT:
                return compare(left, right) < 0;
            case LTE:
                return compare(left, right) <= 0;
            case INCLUDES:
            case CONTAINS:
                return contains(left, right);
            case NOT_CONTAINS:
                return !contains(left, right);
            case IN:
                return inList(left, right);
            case NIN:
                return !inList(left, right);
            case IS_EMPTY:
                return isEmpty(left);
            case IS_NOT_EMPTY:
                return !isEmpty(left);
            case IS_TRUE:
                return isBool(left, true);
            case IS_FALSE:
                return isBool(left, false);
            case STARTS_WITH:
                return asString(left).startsWith(asString(right));
            case ENDS_WITH:
                return asString(left).endsWith(asString(right));
            default:
                return false;
        }
    }

    private static boolean equals(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        Double ln = asDouble(left);
        Double rn = asDouble(right);
        if (ln != null && rn != null) {
            return ln.doubleValue() == rn.doubleValue();
        }
        return asString(left).equals(asString(right));
    }

    private static int compare(Object left, Object right) {
        Double ln = asDouble(left);
        Double rn = asDouble(right);
        if (ln != null && rn != null) {
            return Double.compare(ln, rn);
        }
        return asString(left).compareTo(asString(right));
    }

    @SuppressWarnings("unchecked")
    private static boolean contains(Object left, Object right) {
        if (left == null) {
            return false;
        }
        if (left instanceof Collection) {
            for (Object item : (Collection<Object>) left) {
                if (equals(item, right)) {
                    return true;
                }
            }
            return false;
        }
        if (left instanceof Object[]) {
            for (Object item : (Object[]) left) {
                if (equals(item, right)) {
                    return true;
                }
            }
            return false;
        }
        return asString(left).contains(asString(right));
    }

    /** {@code in}：left 是否属于 right（集合 / 数组按元素相等，标量退化为字符串包含）。 */
    @SuppressWarnings("unchecked")
    private static boolean inList(Object left, Object right) {
        if (right instanceof Collection) {
            for (Object item : (Collection<Object>) right) {
                if (equals(item, left)) {
                    return true;
                }
            }
            return false;
        }
        if (right instanceof Object[]) {
            for (Object item : (Object[]) right) {
                if (equals(item, left)) {
                    return true;
                }
            }
            return false;
        }
        return asString(right).contains(asString(left));
    }

    /** {@code is_empty}：null / 空白串 / 空集合 / 空数组 / 空 Map 均视为空。 */
    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence) {
            return value.toString().trim().isEmpty();
        }
        if (value instanceof Collection) {
            return ((Collection<?>) value).isEmpty();
        }
        if (value instanceof Map) {
            return ((Map<?, ?>) value).isEmpty();
        }
        if (value instanceof Object[]) {
            return ((Object[]) value).length == 0;
        }
        return false;
    }

    /** {@code is_true} / {@code is_false}：兼容布尔、数字（0/非0）、字符串（true/false/1/0）。 */
    private static boolean isBool(Object value, boolean expected) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) == expected;
        }
        if (value instanceof Number) {
            return (((Number) value).doubleValue() != 0) == expected;
        }
        String s = asString(value).trim();
        if (s.equalsIgnoreCase("true") || s.equals("1")) {
            return expected;
        }
        if (s.equalsIgnoreCase("false") || s.equals("0") || s.isEmpty()) {
            return !expected;
        }
        return false;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1d : 0d;
        }
        if (value instanceof String) {
            try {
                return Double.valueOf(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 保留给上层的列表别名，避免 IDE 提示未使用导入。 */
    static boolean isList(Object value) {
        return value instanceof List;
    }
}
