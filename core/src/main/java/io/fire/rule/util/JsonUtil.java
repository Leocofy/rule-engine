package io.fire.rule.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.config.JsonChainLoader;
import io.fire.rule.runtime.TaskExecuteException;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（零外部依赖）。
 *
 * <p>只为解析规则链定义这类结构固定的配置而生，不追求完整 JSON 规范覆盖。
 * 不引入 Jackson/Gson 是为了让引擎核心保持零依赖——
 * 需要完整能力时，替换掉 {@link JsonChainLoader} 的实现即可，不影响引擎其他部分。
 *
 * <p>解析结果：object -> LinkedHashMap，array -> ArrayList，
 * string -> String，number -> Double，true/false -> Boolean，null -> null。
 */
public final class JsonUtil {
    private JsonUtil() {
    }

    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(JsonUtil.class);

    private static final ObjectMapper om = new ObjectMapper();

    public static String toJsonString(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            LOG.error("JSON 序列化失败 [{}]: ", o, e);
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

}
