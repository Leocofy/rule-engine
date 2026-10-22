package io.fire.rule.config;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * 节点自描述注解：让节点成为"可发现组件"。
 *
 * <p>注册表据此建立 type -> 实现类的映射，前端据此渲染配置表单。
 * 这是引擎从"硬编码节点"走向"可扩展节点库"的关键。
 *
 * <p><b>customRelations=true</b> 表示输出关系由运行时决定（如 AI 判定节点），
 * 配置期无法确定，引擎不会校验其下游连线。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
@Documented
public @interface NodeComponent {

    /**
     * 节点类型标识，规则链 JSON 中引用
     */
    String type();

    /**
     * 组件名称，用于前端展示。
     */
    String title() default "";

    /**
     * 分类，用于前端分组展示。
     */
    String category() default "action";

    /**
     * 组件描述，用于前端展示。
     */
    String description() default "";

    /**
     * 声明的输出关系名；为空表示使用默认的 Success / Failure。
     */
    String[] relations() default {};

    /**
     * 输出关系是否由运行时动态决定。
     */
    boolean customRelations() default false;

    /**
     * 当前配置版本号，用于 upgrade 迁移。
     */
    String version() default "1.0";
}
