package io.fire.rule.spi;

/**
 * Description: RuleNodeDefinition
 * SPI 引入必须集成该接口，以保证基础的信息提供
 *
 * @author Cai FeiYue
 * @date 2026/10/22
 */
public interface RuleNodeDefinition extends RuleNode {

    /**
     * 节点类型标识
     */
    String type();

    /**
     * 分类，用于前端分组展示。
     */
    String category();

    /**
     * 组件名称，用于前端展示。
     */
    default String title() {
        return type();
    }

    /**
     * 组件描述，用于前端展示。
     */
    default String description() {
        return null;
    }

    /**
     * 声明的输出关系名；为空表示使用默认的 Success / Failure。
     */
    default String[] relations() {
        return new String[0];
    }

    /**
     * 输出关系是否由运行时动态决定。
     */
    default boolean customRelations() {
        return false;
    }

    /**
     * 当前配置版本号，用于 upgrade 迁移。
     */
    default String version() {
        return "1.0";
    }

}
