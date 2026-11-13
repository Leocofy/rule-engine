package io.fire.rule.spring.flowgram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fire.rule.core.chain.RuleChain;
import io.fire.rule.core.spi.INodeRegistry;
import io.fire.rule.core.spi.NodeException;
import io.fire.rule.core.store.IChainStore;
import io.fire.rule.spring.model.NodeDefinition;
import io.fire.rule.spring.config.SpringNodeRegistry;
import io.fire.rule.spring.flowgram.workflow.WorkflowSchema;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * FlowGram 设计器后端支撑接口（{@code /flowgram/*}），由 {@code cmp} 自动装配，
 * 取代旧的 {@code example} 侧 {@code /designer/*}。
 *
 * <ul>
 *   <li>{@code GET  /flowgram/nodes} —— FlowGram 版节点注册表（含内置 start/end/condition/loop）</li>
 *   <li>{@code GET  /flowgram/workflow} —— WorkflowSchema 图库摘要列表</li>
 *   <li>{@code GET  /flowgram/workflow/{id}} —— 取指定链的原始 WorkflowSchema</li>
 *   <li>{@code POST /flowgram/workflow} —— 保存（覆盖同名链）</li>
 *   <li>{@code DELETE /flowgram/workflow/{id}} —— 删除指定链</li>
 * </ul>
 *
 * @author Cai FeiYue
 * @date 2026-11-03
 */
@RestController
@RequestMapping("/flowgram")
public class FlowGramController {

    private final SpringNodeRegistry nodeRegistry;
    private final INodeRegistry registry;
    private final IChainStore chainStore;
    private final ObjectMapper objectMapper;
    private final WorkflowValidator validator;

    public FlowGramController(SpringNodeRegistry nodeRegistry,
                              IChainStore chainStore,
                              ObjectMapper objectMapper,
                              WorkflowValidator validator) {
        this.nodeRegistry = nodeRegistry;
        this.registry = nodeRegistry;
        this.chainStore = chainStore;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    /**
     * 节点库：注册表里的业务节点定义 + 引擎内置控制流节点，均以 FlowGram 形态透出。
     *
     * <p>响应 {@code { nodes, categories }}：{@code nodes} 按 {@code order} 排序，
     * {@code categories} 是分类展示顺序（组内最小 order 决定先后）。
     */
    @GetMapping("/nodes")
    public Map<String, Object> nodes() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> categories = new ArrayList<>();

        List<NodeDefinition> definitions = new ArrayList<>(nodeRegistry.definitions());
        definitions.sort(NodeDefinition::compareTo);
        for (NodeDefinition definition : definitions) {
            result.add(toFlowGramNode(definition));
            if (!categories.contains(definition.getCategory())) {
                categories.add(definition.getCategory());
            }
        }
        // 控制流节点已由 @NodeComponent 注册表统一提供，不再硬编码第二份
        return Map.of("nodes", result, "categories", categories);
    }

    private Map<String, Object> toFlowGramNode(NodeDefinition definition) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", definition.getType());
        item.put("title", definition.getTitle() == null ? "" : definition.getTitle());
        item.put("category", definition.getCategory() == null ? "other" : definition.getCategory());
        item.put("order", definition.getOrder());
        item.put("description", definition.getDescription() == null ? "" : definition.getDescription());
        item.put("version", definition.getVersion() == null ? "" : definition.getVersion());
        item.put("flowNodeType", definition.getFlowNodeType());
        return item;
    }

    /**
     * 图库摘要列表。
     */
    @GetMapping("/workflow")
    public List<Map<String, Object>> workflows() {
        List<Map<String, Object>> result = new ArrayList<>();
        Collection<Object> chains = chainStore.getChains();
        if (chains == null) {
            return result;
        }
        for (Object raw : chains) {
            JsonNode doc = toTree(raw);
            if (doc == null || !doc.isObject()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", text(doc.get("id")));
            item.put("name", text(doc.get("name")));
            item.put("nodeCount", doc.path("nodes").size());
            item.put("edgeCount", doc.path("edges").size());
            result.add(item);
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("id"))));
        return result;
    }

    /**
     * 读取指定链的原始 WorkflowSchema。
     */
    @GetMapping(value = "/workflow/{chainId}", produces = "application/json;charset=UTF-8")
    public Object workflow(@PathVariable String chainId) {
        Object raw = chainStore.getChainRawData(chainId);
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "规则链不存在: " + chainId);
        }
        return raw;
    }

    /**
     * 保存（覆盖同名链）：装载校验通过后落盘，返回摘要。
     */
    @PostMapping(value = "/workflow", produces = "application/json;charset=UTF-8")
    public Map<String, Object> save(@RequestBody JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则链必须是 JSON 对象");
        }
        JsonNode workflowNode = body.has("workflow") ? body.get("workflow") : body;
        JsonNode schemasNode = body.get("schemas");
        if (workflowNode == null || !workflowNode.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflow 必须是 JSON 对象");
        }
        String requestedId = resolveId(body, workflowNode);
        if (requestedId != null && !requestedId.isEmpty()) {
            workflowNode = withId(workflowNode, requestedId);
        }
        RuleChain chain;
        try {
            WorkflowSchema workflow = WorkflowSchema.parse(workflowNode.toString());
            validator.validate(workflow, schemasNode);
            chain = chainStore.registerChain(workflowNode.toString());
        } catch (NodeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        result.put("chainId", chain.getId());
        result.put("chainName", chain.getName());
        result.put("savedAt", System.currentTimeMillis());
        return result;
    }

    /**
     * 删除指定链。
     */
    @DeleteMapping("/workflow/{chainId}")
    public Map<String, Object> delete(@PathVariable String chainId) {
        RuleChain removed = chainStore.deleteChain(chainId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", removed != null);
        result.put("chainId", chainId);
        return result;
    }

    /**
     * 统一错误响应：把 exception message 显式带回，供前端展示保存失败原因。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleError(ResponseStatusException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getStatus().value());
        body.put("message", e.getReason() == null || e.getReason().isEmpty() ? "请求处理失败" : e.getReason());
        return ResponseEntity.status(e.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private String resolveId(JsonNode body, JsonNode workflowNode) {
        String id = text(body.get("id"));
        if (id == null || id.isEmpty()) {
            id = text(body.get("chainId"));
        }
        if ((id == null || id.isEmpty()) && workflowNode != null) {
            id = text(workflowNode.get("id"));
        }
        return id;
    }

    private JsonNode withId(JsonNode workflowNode, String id) {
        if (id == null || id.isEmpty() || workflowNode == null || !workflowNode.isObject()) {
            return workflowNode;
        }
        ObjectNode copy = ((ObjectNode) workflowNode).deepCopy();
        copy.put("id", id);
        return copy;
    }

    private JsonNode toTree(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return raw instanceof String ? objectMapper.readTree((String) raw) : objectMapper.valueToTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }
}
