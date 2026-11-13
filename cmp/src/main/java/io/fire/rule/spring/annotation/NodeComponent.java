package io.fire.rule.spring.annotation;

import io.fire.rule.spring.model.NodeDefinition;
import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * 节点自描述注解：让节点成为"可发现组件"。
 *
 * <p>注册表据此建立 type -> 实现类的映射，前端据此构建可用节点清单；
 * 节点 schema 由前端维护，本注解不再承载 schema 定义。
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
    String name() default "";

    /**
     * 分类，用于前端分组展示。
     */
    String category() default "action";

    /**
     * 展示排序权重，由后端决定节点库的呈现顺序。
     *
     * <p>值越小越靠前。分类分组的先后取该分组内最小的 {@code order}，
     * 组内节点再按 {@code order}、类型名依次排列。未声明时取
     * {@value NodeDefinition#DEFAULT_ORDER}，排在显式声明的节点之后。
     */
    int order() default NodeDefinition.DEFAULT_ORDER;

    /**
     * 组件描述，用于前端展示。
     */
    String description() default "";

    /**
     * 当前配置版本号，用于升级迁移。
     */
    String version() default "1.0";
}
