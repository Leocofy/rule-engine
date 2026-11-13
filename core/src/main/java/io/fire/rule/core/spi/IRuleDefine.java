package io.fire.rule.core.spi;

/**
 * Description: RuleDefine
 *
 * @author Cai FeiYue
 * @date 2026/11/9
 */
public interface IRuleDefine {

    String type();

    /**
     * 声明的输出关系名；为空表示使用默认的 default。
     */
    default String[] relations() {
        return new String[0];
    }

}
