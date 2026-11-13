package io.fire.rule.core.spi;

/**
 * Description: INodeRegistry
 *
 * @author Cai FeiYue
 * @date 2026/10/16
 */
public interface INodeRegistry {

    RuleNode get(String type) throws NodeException;

    <T> T get(String type, Class<T> requireClazz) throws NodeException;

}
