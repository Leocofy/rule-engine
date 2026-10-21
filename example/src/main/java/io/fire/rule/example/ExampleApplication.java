package io.fire.rule.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Example 启动类：演示如何使用 rule-engine-spring-boot-starter。
 *
 * <p>starter 的自动配置会自动装配 {@link io.fire.rule.RuleEngine}，
 * 业务侧只需提供节点服务 Bean 和规则链 JSON 即可。
 *
 * @author Cai FeiYue
 * @date 2026/10/21
 */
@SpringBootApplication
public class ExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}
