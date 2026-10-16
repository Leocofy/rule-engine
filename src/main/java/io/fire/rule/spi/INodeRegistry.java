package io.fire.rule.spi;

/**
 * Description: INodeRegistry
 *
 * @author Cai FeiYue
 * @date 2026/10/16
 */
public interface INodeRegistry {

    RuleNode get(String type) throws NodeException;

    <T> T get(Class<T> clazz) throws NodeException;

}
