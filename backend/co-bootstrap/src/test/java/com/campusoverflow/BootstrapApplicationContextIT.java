package com.campusoverflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.bootstrap.demo.DemoDataSeeder;
import com.campusoverflow.bootstrap.outbox.OutboxRelay;
import com.campusoverflow.bootstrap.web.GlobalExceptionHandler;
import com.campusoverflow.bootstrap.web.WebConfig;
import io.swagger.v3.oas.models.OpenAPI;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 完整应用上下文冒烟（Issue #97）：真实 MySQL 8 + Redis 7 之下 @SpringBootTest 起得来，
 * 装配层的关键 Bean 都在，且 demo 种子不会在非 demo 环境被装配。
 *
 * <p>{@code disabledWithoutDocker = true}：本机 / 无 Docker 的 runner 上整类跳过而不是报
 * "Could not find a valid Docker environment"，让 {@code mvn verify} 的绿/红只反映真实可验证的
 * 内容；CI runner 有 Docker 时照常执行，不降低集成测试的有效性。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration"
})
class BootstrapApplicationContextIT {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.39"))
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
                    "--ngram-token-size=2");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    ApplicationContext context;

    @Test
    void 完整应用上下文能启动且装配层关键Bean齐全() {
        assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(context.getBean(OpenAPI.class).getInfo().getTitle()).isEqualTo("CampusOverflow API");
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).isNotEmpty();
        assertThat(context.getBeansOfType(JdbcTemplate.class)).isNotEmpty();
        assertThat(context.getBeansOfType(OutboxRelay.class)).isNotEmpty();
        assertThat(context.getBeansOfType(WebConfig.class)).isNotEmpty();
        assertThat(context.getBeansOfType(GlobalExceptionHandler.class)).isNotEmpty();
    }

    @Test
    void demo种子在非demo环境下不会被装配() {
        assertThat(context.getBeanNamesForType(DemoDataSeeder.class)).isEmpty();
    }
}
