package io.fire.rule.core.store;

import io.fire.rule.core.chain.RuleChain;
import io.fire.rule.core.config.IChainLoader;
import io.fire.rule.core.spi.NodeException;
import io.fire.rule.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 回归：验证 {@link InMemoryChainStore} 在未配置持久化目录时注册链不应失败，
 * 以及传入 Map 形式的链数据时应落盘为可重新解析的合法 JSON。
 */
public class InMemoryChainStoreTest {

    private static final IChainLoader LOADER = data -> {
        Map<String, Object> map = asMap(data);
        return new RuleChain(JsonUtil.asString(map.get("id")), JsonUtil.asString(map.get("name")));
    };

    // Test scenario: savePath 为 null（默认构造）时 registerChain 只做内存注册，不应抛异常。
    @Test
    public void registerChainWithoutSavePathDoesNotThrow() {
        InMemoryChainStore store = new InMemoryChainStore(LOADER);
        Map<String, Object> data = JsonUtil.parseObject("{\"id\":\"chain-null\",\"name\":\"n\"}");

        RuleChain chain = assertDoesNotThrow(() -> store.registerChain(data));

        assertEquals("chain-null", chain.getId());
        assertSame(chain, store.getChain("chain-null"), "内存中应可按 id 取回链");
        assertSame(data, store.getChainRawData("chain-null"), "原始链数据应被保存");
    }

    // Test scenario: 传入 Map 时应落盘为合法 JSON，且重启后的新实例可重新装载该链。
    @Test
    public void registerMapPersistsLegalJsonAndCanBeReloaded(@TempDir Path tempDir) throws Exception {
        InMemoryChainStore store = new InMemoryChainStore(LOADER, tempDir.toString());
        Map<String, Object> data = JsonUtil.parseObject(
                "{\"id\":\"chain-persist\",\"name\":\"n\",\"tags\":[\"a\",\"b\"]}");

        store.registerChain(data);

        Path file = tempDir.resolve("chain-persist.json");
        assertTrue(Files.exists(file), "Map 链数据应落盘为同名 JSON 文件");

        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Object parsed = assertDoesNotThrow(() -> JsonUtil.parseAny(json), "落盘内容必须是合法 JSON");
        assertTrue(parsed instanceof Map, "落盘内容应仍是 JSON 对象");
        Map<String, Object> parsedMap = asMap(parsed);
        assertEquals("chain-persist", parsedMap.get("id"));
        assertEquals("n", parsedMap.get("name"));
        assertEquals(Arrays.asList("a", "b"), parsedMap.get("tags"), "嵌套数组应完整落盘");

        InMemoryChainStore reloaded = new InMemoryChainStore(LOADER, tempDir.toString());
        RuleChain loaded = reloaded.getChain("chain-persist");
        assertNotNull(loaded, "新实例应能从磁盘重新装载链");
        assertEquals("n", loaded.getName(), "重新装载后链属性应保持一致");
    }

    // Test scenario: 传入 JSON 字符串时应按原样落盘，不进行额外转义或破坏。
    @Test
    public void registerStringChainPersistsRawJson(@TempDir Path tempDir) throws Exception {
        InMemoryChainStore store = new InMemoryChainStore(LOADER, tempDir.toString());
        String raw = "{\"id\":\"chain-raw\",\"name\":\"raw-name\"}";

        store.registerChain(raw);

        Path file = tempDir.resolve("chain-raw.json");
        assertTrue(Files.exists(file));
        assertEquals(raw, new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                "字符串形式的链数据应原样落盘");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object data) {
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return JsonUtil.parseObject(String.valueOf(data));
    }

    // Test scenario: 持久化目录非法（被普通文件占用）时，registerChain 应转成 NodeException 并保留 cause。
    @Test
    public void registerChainWrapsPersistFailureAsNodeException(@TempDir Path tempDir) throws Exception {
        Path notDirectory = tempDir.resolve("not-a-dir.txt");
        Files.write(notDirectory, "occupied".getBytes(StandardCharsets.UTF_8));
        InMemoryChainStore store = new InMemoryChainStore(LOADER, notDirectory.toString());
        Map<String, Object> data = JsonUtil.parseObject("{\"id\":\"chain-bad\",\"name\":\"n\"}");

        NodeException error = assertThrows(NodeException.class, () -> store.registerChain(data));

        assertTrue(error.getMessage().contains("保存规则链失败"), "应保留保存失败语义");
        assertTrue(error.getCause() instanceof java.io.IOException, "应保留底层 IO 异常为 cause");
    }
}