package io.fire.rule.core.util;

/**
 * Description: StrUtil
 *
 * @author Cai FeiYue
 * @date 2026/10/28
 */
public class StrUtil {
    private StrUtil() {
    }

    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    public static String asString(Object str) {
        return str == null ? "" : str.toString();
    }

}
