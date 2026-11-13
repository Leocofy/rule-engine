package io.fire.rule.spring.flowgram.node;

import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.core.spi.NodeException;
import io.fire.rule.core.util.JsonUtil;
import io.fire.rule.spring.annotation.NodeComponent;
import io.fire.rule.spring.base.SampleRuleNode;
import io.fire.rule.spring.flowgram.WorkflowConstants;
import io.fire.rule.spring.flowgram.node.config.ScriptConfig;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脚本节点：执行 JavaScript（Nashorn），脚本通过 {@code function main(params)} 读取输入并返回产出。
 */
@NodeComponent(type = WorkflowConstants.TYPE_CODE, name = "脚本", category = "基础",
        order = 10, description = "执行 JavaScript 脚本，params 为节点输入，返回值为节点产出")
public final class ScriptNode extends SampleRuleNode<ScriptConfig> {

    @Override
    protected void onMsg0(INodeContext ctx, ScriptConfig config) throws Exception {
        if (config == null || config.getScript() == null
                || config.getScript().getContent() == null
                || config.getScript().getContent().trim().isEmpty()) {
            throw new NodeException("脚本内容不能为空", true);
        }
        String language = config.getScript().getLanguage();
        if (language != null && !language.trim().isEmpty()
                && !"javascript".equalsIgnoreCase(language) && !"js".equalsIgnoreCase(language)) {
            throw new NodeException("暂只支持 javascript 脚本，当前语言: " + language, true);
        }

        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("nashorn");
        if (engine == null) {
            engine = manager.getEngineByName("javascript");
        }
        if (engine == null) {
            throw new NodeException("当前运行环境未安装 JavaScript 引擎，无法执行脚本节点", true);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> rawInputs = JsonUtil.asObject(
                ctx.nodeConfig().get(WorkflowConstants.INPUTS_VALUES));
        rawInputs.forEach((key, value) -> params.put(key, ctx.resolveValue(value)));
        Object result;
        try {
            Object evalResult = engine.eval(config.getScript().getContent());
            Object main = engine.get("main");
            result = main != null && engine instanceof Invocable
                    ? ((Invocable) engine).invokeFunction("main", params)
                    : evalResult;
        } catch (ScriptException | NoSuchMethodException e) {
            throw new NodeException("脚本执行失败: " + e.getMessage(), true);
        }

        if (result == null) {
            ctx.emit(Collections.emptyMap());
        } else if (result instanceof Map) {
            ctx.emit(result);
        } else {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("result", result);
            ctx.emit(outputs);
        }
        ctx.next();
    }
}