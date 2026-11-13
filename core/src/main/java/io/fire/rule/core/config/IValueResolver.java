package io.fire.rule.core.config;

import io.fire.rule.core.model.ExecutionState;
import io.fire.rule.core.model.NodeConfig;
import io.fire.rule.core.spi.IExpressionEngine;
import io.fire.rule.core.spi.INodeContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Description: IValueResolver —— 运行期取值抽象（core 只定义契约、不绑定任何具体取值方言）。
 *
 * <p>把节点声明的"输入取值描述"在运行期坍缩成真实值：core 不感知具体 DSL（如 flowgram 的判别式取值），
 * {@code INodeContext#resolvedInputs} 委托，使"从配置的哪个字段、如何取输入记录"这一 DSL 细节留在实现方。
 *
 * @author Cai FeiYue
 * @date 2026/11/6
 */
@FunctionalInterface
public interface IValueResolver {

    Object resolveOne(Object raw, INodeContext ctx, IExpressionEngine engine);

}
