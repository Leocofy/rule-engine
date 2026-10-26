package io.fire.rule.store;

import io.fire.rule.chain.RuleChain;
import io.fire.rule.config.IChainLoader;
import io.fire.rule.runtime.TaskExecuteException;
import io.fire.rule.spi.NodeException;
import io.fire.rule.util.JsonUtil;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final String savePath;

    public InMemoryChainStore(IChainLoader loader) {
        this(loader, null);
    }

    public InMemoryChainStore(IChainLoader loader, String savePath) {
        this.loader = loader;
        this.savePath = savePath;
        init();
    }

    public void init() {
        if (savePath != null) {
            try (FileInputStream inputStream = new FileInputStream(savePath)) {
                Map<String, Object> map = JsonUtil.readValue(inputStream, Map.class);
                if (map != null) {
                    map.forEach((k, v) -> {
                        try {
                            registerChain(v);
                        } catch (NodeException e) {
                            throw new TaskExecuteException(e);
                        }
                    });
                }
            } catch (IOException e) {
                throw new TaskExecuteException("Failed to load chain data from file", e);
            }
        }
    }

    @Override
    public RuleChain deleteChain(String chainId) {
        chainDataMap.remove(chainId);
        return chainMap.remove(chainId);
    }

    @Override
    public RuleChain registerChain(Object chainData) throws NodeException {
        RuleChain chain = loader.load(chainData);
        String chainId = chain.getId() != null ? chain.getId() : UUID.randomUUID().toString();
        chainDataMap.compute(chainId, (id, data) -> {
            chain.setId(id);
            chainMap.put(id, chain);
            return chainData;
        });
        return chain;
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
