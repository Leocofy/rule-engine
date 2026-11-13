package io.fire.rule.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.core.runtime.TaskExecuteException;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具：解析 / 序列化 / 类型绑定。
 *
 * <p>为解析结构固定的链配置而生。核心不绑定强类型业务，
 * 结构以 {@code Map}/{@code List}/标量承载。
 *
 * <p>解析结果：object -> LinkedHashMap，array -> ArrayList，
 * string -> String，number -> Double，true/false -> Boolean，null -> null。
 */
public final class JsonUtil {
    private JsonUtil() {
    }

    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(JsonUtil.class);

    private static final ObjectMapper om = new ObjectMapper();

    /** 绑定专用：忽略未知键，缺失键保留目标类型的字段初始值（=默认），供 {@link #convert} 使用。 */
    private static final ObjectMapper BIND_MAPPER = om.copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static String toJsonString(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            LOG.error("JSON 序列化失败 [{}]: ", o, e);
            throw new TaskExecuteException("Invalid JSON data", e);
        }
    }

    public static <T> T readValue(InputStream stream, Type type) {
        try {
            return om.readValue(stream, om.constructType(type));
        } catch (IOException e) {
            LOG.error("JSON stream 解析失败 [{}]: ", type, e);
            throw new TaskExecuteException("Invalid JSON data", e);
        }
    }

    public static <T> T readValue(String json, Type type) {
        try {
            return om.readValue(json, om.constructType(type));
        } catch (IOException e) {
            LOG.error("JSON 解析失败 [{}]: ", json, e);
            throw new TaskExecuteException("Invalid JSON data", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        try {
            return (Map<String, Object>) om.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            LOG.error("JSON 解析失败 [{}]: ", json, e);
            throw new TaskExecuteException("Invalid JSON data", e);
        }
    }

    /**
     * 解析任意 JSON（对象或数组皆可），返回 Map / List / String / Double / Boolean / null。
     */
    public static Object parseAny(String json) {
        try {
            return om.readValue(json, Object.class);
        } catch (IOException e) {
            LOG.error("JSON 解析失败 [{}]: ", json, e);
            throw new TaskExecuteException("Invalid JSON data", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(byte[] jsonBit) {
        try {
            return (Map<String, Object>) om.readValue(jsonBit, Map.class);
        } catch (IOException e) {
            LOG.error("JSON 解析失败 [{}]: ", new String(jsonBit, StandardCharsets.UTF_8), e);
            throw new TaskExecuteException("Invalid JSON data", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        if (o instanceof String) {
            return parseObject((String) o);
        }
        return (Map<String, Object>) om.convertValue(o, Map.class);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object o) {
        if (o == null) {
            return new ArrayList<>();
        }
        if (o instanceof List) {
            return (List<Object>) o;
        }
        return om.convertValue(o, List.class);
    }

    public static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * 把已解析的 JSON 结构（Map / List / POJO）绑定到目标类型：未知键忽略、缺失键保留目标类型的
     * 字段初始值（默认）。供 {@code INodeContext#bind} 将节点配置 props 还原为强类型配置对象使用。
     *
     * @param source 源结构（{@code null} 视为空对象，返回全默认的 type 实例）
     * @param type   目标类型
     * @return 绑定后的实例
     */
    public static <T> T convert(Object source, Class<T> type) {
        try {
            return BIND_MAPPER.convertValue(source == null ? Collections.emptyMap() : source, type);
        } catch (IllegalArgumentException e) {
            LOG.error("配置绑定失败 [{} -> {}]: ", source, type, e);
            throw new TaskExecuteException("Invalid config for " + type.getSimpleName(), e);
        }
    }

}
