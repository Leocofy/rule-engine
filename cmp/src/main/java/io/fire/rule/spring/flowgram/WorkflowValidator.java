package io.fire.rule.spring.flowgram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fire.rule.core.chain.RuleChain;
import io.fire.rule.core.spi.INodeRegistry;
import io.fire.rule.core.spi.NodeException;
import io.fire.rule.spring.flowgram.schema.JsonSchemaConfig;
import io.fire.rule.spring.flowgram.util.JsonSchemaValidator;
import io.fire.rule.spring.flowgram.workflow.WorkflowNode;
import io.fire.rule.spring.flowgram.workflow.WorkflowSchema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Workflow 保存/校验的统一入口：结构校验 + 前端提交 schema 的即时校验。
 *
 * <p>schema 只从本次请求的 {@code schemas} 读取，验证后即丢弃；后端不缓存、不落库。
 * 字段式输入用 {@code data.inputsValues} 校验，结构式输入（conditions/loopFor/loopOutputs）
 * 直接校验 {@code data}。
 */
public final class WorkflowValidator {

    private final INodeRegistry registry;

    private final ObjectMapper objectMapper;

    public WorkflowValidator(INodeRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * 校验并返回装载后的链（供调用方取 chainId/entry），不落盘。
     */
    public RuleChain validate(WorkflowSchema workflow, JsonNode schemas) throws NodeException {
        RuleChain chain = new WorkflowSchemaLoader(registry).load(workflow.root());
        if (schemas != null && schemas.isObject()) {
            validateSchemas(workflow.nodes(), schemas);
        }
        return chain;
    }

    private void validateSchemas(List<WorkflowNode> nodes, JsonNode schemas) throws NodeException {
        for (WorkflowNode node : nodes) {
            JsonNode schemaNode = schemas.get(node.type());
            if (schemaNode != null && schemaNode.isObject()) {
                // 所有节点 schema 默认走官方 IJsonSchema 强模型
                JsonSchemaConfig schemaConfig = objectMapper.convertValue(schemaNode, JsonSchemaConfig.class);
                Object schema = objectMapper.convertValue(schemaConfig, Map.class);
                List<String> errors = JsonSchemaValidator.validate(schema, schemaTarget(node));
                if (!errors.isEmpty()) {
                    throw new NodeException("节点 " + node.id() + " 配置不合法: "
                            + String.join("; ", errors), true);
                }
            }
            if (!node.blocks().isEmpty()) {
                validateSchemas(node.blocks(), schemas);
            }
        }
    }

    private Object schemaTarget(WorkflowNode node) {
        Map<String, Object> data = node.data();
        if (data.containsKey("conditions")
                || data.containsKey("loopFor")
                || data.containsKey("loopOutputs")
                || data.containsKey("script")
                || data.containsKey("assign")
                || data.containsKey("branch")) {
            return data;
        }
        Object inputsValues = data.get("inputsValues");
        return inputsValues == null ? Collections.emptyMap() : inputsValues;
    }
}