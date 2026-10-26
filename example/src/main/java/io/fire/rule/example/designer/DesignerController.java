package io.fire.rule.example.designer;

import io.fire.rule.config.RuleEngineProperties;
import io.fire.rule.runtime.TaskExecuteException;
import io.fire.rule.spi.INodeRegistry;
import io.fire.rule.spi.NodeDefinition;
import io.fire.rule.spi.NodeException;
import io.fire.rule.store.IChainStore;
import io.fire.rule.util.JsonUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 规则链设计器的后端支撑接口。
 *
 * <p>静态页面由 {@code static/designer/index.html} 提供（web 模块构建产物），
 * 这里额外暴露节点库与示例链路，避免前端把节点定义写死：
 * <ul>
 *   <li>{@code GET /designer/nodes} —— 当前 INodeRegistry 中的节点定义</li>
 *   <li>{@code GET /designer/example} —— 当前引擎加载的规则链 JSON</li>
 * </ul>
 *
 * @author Cai FeiYue
 * @date 2026/10/23
 */
@RestController
@RequestMapping("/designer")
public class DesignerController {

    private static final String DEFAULT_CHAIN_PATH = "fire-alarm-chain.json";

    private final INodeRegistry nodeRegistry;

    private final RuleEngineProperties properties;

    private final IChainStore chainStore;

    public DesignerController(INodeRegistry nodeRegistry, RuleEngineProperties properties, IChainStore chainStore) {
        this.nodeRegistry = nodeRegistry;
        this.properties = properties;
        this.chainStore = chainStore;
    }

    /** 访问 /designer 或 /designer/ 时转发到单页应用入口。 */
    @GetMapping({"", "/"})
    public ModelAndView index() {
        return new ModelAndView("forward:/designer/index.html");
    }

    /** 节点库：直接暴露注册表里的定义，前端据此渲染画布节点与出口。 */
    @GetMapping("/nodes")
    public List<Map<String, Object>> nodes() {
        List<NodeDefinition> definitions = new ArrayList<NodeDefinition>(nodeRegistry.definitions());
        definitions.sort(Comparator
                .comparing((NodeDefinition def) -> def.getCategory() == null ? "" : def.getCategory())
                .thenComparing(def -> def.getType() == null ? "" : def.getType()));

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>(definitions.size());
        for (NodeDefinition definition : definitions) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("type", definition.getType());
            item.put("title", definition.getTitle() == null ? "" : definition.getTitle());
            item.put("category", definition.getCategory() == null ? "other" : definition.getCategory());
            item.put("description", definition.getDescription() == null ? "" : definition.getDescription());
            item.put("version", definition.getVersion() == null ? "" : definition.getVersion());
            item.put("relations", definition.getRelations() == null ? new String[0] : definition.getRelations());
            item.put("customRelations", definition.isCustomRelations());
            result.add(item);
        }
        return result;
    }

    /** 示例链路：返回引擎实际加载的 JSON，便于设计器“加载示例”。 */
    @GetMapping(value = "/example", produces = "application/json;charset=UTF-8")
    public Object example() {
        Collection<Object> chains = chainStore.getChains();
        if (chains == null || chains.isEmpty()) {
            return JsonUtil.parseObject("{\"id\":\"empty\",\"name\":\"未配置规则链\",\"nodes\":{},\"edges\":{},\"entry\":[]}");
        }
        return chains.stream().findFirst()
                .orElse(JsonUtil.parseObject("{\"id\":\"empty\",\"name\":\"未配置规则链\",\"nodes\":{},\"edges\":{},\"entry\":[]}"));
    }

    /** 示例链路：返回引擎实际加载的 JSON，便于设计器“加载示例”。 */
    @PostMapping(value = "/save", produces = "application/json;charset=UTF-8")
    public void save(@RequestBody String map) {
        try {
            chainStore.registerChain(map);
        } catch (NodeException e) {
            throw new TaskExecuteException(e);
        }
    }
}
