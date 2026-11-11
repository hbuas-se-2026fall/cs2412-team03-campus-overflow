package com.campusoverflow.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.bootstrap.config.OpenApiConfig;
import com.campusoverflow.bootstrap.config.TimeConfig;
import com.campusoverflow.bootstrap.security.SecurityConfig;
import com.campusoverflow.bootstrap.web.ActorArgumentResolver;
import com.campusoverflow.bootstrap.web.WebConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

/**
 * 装配层配置类的上下文契约：配置类能组成可用上下文、关键 Bean 存在且行为正确，
 * 以及 application.yml 里的键确实对得上代码中的 {@code @Value} 占位符。
 *
 * <p>这里刻意不伪造一个「完整 @SpringBootTest」：真实应用上下文需要 MySQL 8 + Redis 7，
 * 由 {@code CampusOverflowFlowIT} / {@code QuestionPersistenceIT} 与
 * {@code BootstrapApplicationContextIT} 在具备 Docker 的环境里承担。本机无 Docker 时不造
 * 一个排除掉所有数据库装配的假上下文来冒充「应用起得来」。
 */
class BootstrapContextTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(TimeConfig.class, OpenApiConfig.class, WebConfig.class);

    @Test
    void 装配层配置类能组成可用上下文并暴露关键Bean() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(Clock.class);
            assertThat(ctx).hasSingleBean(OpenAPI.class);
            assertThat(ctx).hasSingleBean(WebConfig.class);

            assertThat(ctx.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(ctx.getBean(OpenAPI.class).getInfo().getTitle()).isEqualTo("CampusOverflow API");
        });
    }

    @Test
    void WebConfig把Actor解析器注册进MVC参数解析链() {
        context.run(ctx -> {
            List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
            ctx.getBean(WebConfig.class).addArgumentResolvers(resolvers);

            assertThat(resolvers).hasSize(1);
            assertThat(resolvers.get(0)).isInstanceOf(ActorArgumentResolver.class);
        });
    }

    @Test
    void 安全上下文仓储同时委托请求属性与HTTP会话() {
        assertThat(new SecurityConfig(new ObjectMapper()).securityContextRepository())
                .isInstanceOf(DelegatingSecurityContextRepository.class);
    }

    @Test
    void applicationYml里的发件箱键与Relay的属性占位符一致() throws Exception {
        PropertySource<?> source = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .get(0);

        // Relay 的 @Value / @Scheduled 都引用这些键；键名改错时注解默认值会悄悄兜底，
        // 所以要显式守住配置文件一侧，而不是只测代码里的默认值。
        assertThat(source.getProperty("co.outbox.batch-size")).isEqualTo(100);
        assertThat(source.getProperty("co.outbox.max-attempts")).isEqualTo(10);
        assertThat(source.getProperty("co.outbox.poll-interval-ms")).isEqualTo(500);
        assertThat(source.getProperty("co.outbox.cleanup-cron")).isEqualTo("0 0 4 * * *");
    }
}
