package io.fire.rule.core.store;

import io.fire.rule.core.chain.RuleChain;
import io.fire.rule.core.config.IChainLoader;
import io.fire.rule.core.spi.NodeException;
import io.fire.rule.core.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Description: MemoryChainStore
 *
 * @author Cai FeiYue
 * @date 2026/10/23
 */
public class InMemoryChainStore implements IChainStore {

    private final Map<String, RuleChain> chainMap = new ConcurrentHashMap<>();
    private final Map<String, Object> chainDataMap = new ConcurrentHashMap<>();

    private final IChainLoader loader;
    private final Path savePath;

    public InMemoryChainStore(IChainLoader loader) {
        this(loader, null);
    }

    public InMemoryChainStore(IChainLoader loader, String savePath) {
        this.loader = loader;
        this.savePath = savePath != null ? Paths.get(savePath) : null;
        init();
    }

    public void init() {
        if (savePath != null) {
            for (String json : loadAll()) {
                registerChain0(json);
            }
        }
    }

    /**
     * 读取全部已保存的规则链，按文件名排序保证启动装载顺序稳定。
     */
    public List<String> loadAll() {
        if (!Files.isDirectory(savePath)) {
            return Collections.emptyList();
        }
        List<String> chains = new ArrayList<>();
        try (Stream<Path> files = Files.list(savePath)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            chains.add(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                        } catch (IOException ignore) {
                        }
                    });
        } catch (IOException e) {
            return Collections.emptyList();
        }
        return chains;
    }

    /**
     * 删除落盘文件，不存在时返回 false。
     */
    public boolean delete(String chainId) {
        try {
            return Files.deleteIfExists(fileOf(chainId));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 覆盖写：先写临时文件再替换，避免保存过程中被打断产生半截 JSON。
     */
    public void save(String chainId, String json) throws IOException {
        if (savePath == null) {
            // 未配置持久化目录：只做内存注册，不能因为"没地方存"让注册失败
            return;
        }
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("规则链内容为空，无法保存");
        }
        Files.createDirectories(savePath);
        Path target = fileOf(chainId);
        Path temp = Files.createTempFile(savePath, "chain-", ".tmp");
        Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path fileOf(String chainId) {
        String name = chainId == null ? "" : chainId.trim();
        if (name.isEmpty()) {
            name = "chain";
        }
        // 链 id 可能包含中文或斜杠，统一收敛成安全的文件名
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        return savePath.resolve(name + ".json");
    }

    @Override
    public RuleChain deleteChain(String chainId) {
        delete(chainId);
        chainDataMap.remove(chainId);
        return chainMap.remove(chainId);
    }

    @Override
    public RuleChain registerChain(Object chainData) throws NodeException {
        RuleChain chain = registerChain0(chainData);
        try {
            save(chain.getId(), toJson(chainData));
        } catch (IOException e) {
            throw new NodeException("保存规则链失败", e);
        }
        return chain;
    }

    /**
     * 原始链数据可能是 JSON 字符串，也可能是已解析的 Map/List。
     * 前者直接落盘，后者必须先序列化，否则 {@code toString()} 写出的是非法 JSON。
     */
    private static String toJson(Object chainData) {
        return chainData instanceof String ? (String) chainData : JsonUtil.toJsonString(chainData);
    }

    private RuleChain registerChain0(Object chainData) {
        RuleChain chain = loader.load(chainData);
        if (chain.getId() == null || chain.getId().trim().isEmpty()) {
            throw new NodeException("规则链未设置 ID", true);
        }
        String chainId = chain.getId();
        chainDataMap.compute(chainId, (id, data) -> {
            chain.setId(id);
            chainMap.put(id, chain);
            return chainData;
        });
        // 内联子链（如循环体）随主链登记，供执行器按 ID 取用
        registerSubChains(chain);
        return chain;
    }

    private void registerSubChains(RuleChain parent) {
        for (Map.Entry<String, RuleChain> entry : parent.subChains().entrySet()) {
            RuleChain sub = entry.getValue();
            sub.setId(entry.getKey());
            chainMap.putIfAbsent(entry.getKey(), sub);
            registerSubChains(sub);
        }
    }

    @Override
    public Object getChainRawData(String chainId) {
        return chainDataMap.get(chainId);
    }

    @Override
    public RuleChain getChain(String chainId) {
        return chainMap.get(chainId);
    }

    @Override
    public Collection<Object> getChains() {
        return chainDataMap.values();
    }

}
