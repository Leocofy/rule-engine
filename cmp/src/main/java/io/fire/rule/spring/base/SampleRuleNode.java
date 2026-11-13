package io.fire.rule.spring.base;

import io.fire.rule.core.spi.INodeContext;
import io.fire.rule.core.spi.RuleNode;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Description: SampleRuleNode
 *
 * @author Cai FeiYue
 * @date 2026/11/10
 */
public abstract class SampleRuleNode<T> implements RuleNode, NodeConfigAware {

    private final Class<T> _configType;

    @SuppressWarnings("unchecked")
    protected SampleRuleNode() {
        Type superClass = this.getClass().getGenericSuperclass();
        if (superClass instanceof Class) {
            throw new IllegalArgumentException("Internal error: TypeReference constructed without actual type information");
        }
        Type arg = ((ParameterizedType) superClass).getActualTypeArguments()[0];
        this._configType = arg instanceof Class
                ? (Class<T>) arg
                : (Class<T>) ((ParameterizedType) arg).getRawType();
    }

    @Override
    public void onMsg(INodeContext ctx) throws Exception {
        T config = ctx.config(_configType);
        onMsg0(ctx, config);
    }

    @Override
    public Class<T> configType() {
        return _configType;
    }

    protected abstract void onMsg0(INodeContext ctx, T config) throws Exception;

}
