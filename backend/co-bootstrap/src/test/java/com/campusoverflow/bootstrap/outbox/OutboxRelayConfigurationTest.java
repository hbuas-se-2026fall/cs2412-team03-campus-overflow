package com.campusoverflow.bootstrap.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusoverflow.shared.util.UtcTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 发件箱中继的配置绑定与调度契约。
 *
 * <p>用真实 Spring 上下文创建 {@link OutboxRelay}（而不是 new 出来），这样
 * {@code @Value("${co.outbox.batch-size:100}")} / {@code max-attempts} /
 * {@code @Scheduled} 的占位符确实由 Environment 解析。绑定是否生效不靠反射读私有字段，
 * 而是看 relay() 认领 SQL 里真实传入的 attempts 上限与批大小；清理任务则断言七天这个
 * 保留期，避免哪天把清理窗口改错而无人发现。
 */
class OutboxRelayConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T02:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void transactionTemplateExecutesItsCallback() {
        when(tx.execute(any())).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(jdbc.queryForList(anyString(), eq(Long.class), any(), any(), any()))
                .thenReturn(List.of());
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(NamedParameterJdbcTemplate.class, () -> named)
                .withBean(TransactionTemplate.class, () -> tx)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(OutboxRelay.class);
    }

    @Test
    void 自定义属性被注入到认领SQL的批大小与最大重试次数() {
        runner()
                .withPropertyValues("co.outbox.batch-size=7", "co.outbox.max-attempts=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OutboxRelay.class);

                    context.getBean(OutboxRelay.class).relay();

                    verify(jdbc).queryForList(contains("FOR UPDATE SKIP LOCKED"), eq(Long.class),
                            eq(3), any(), eq(7));
                });
    }

    @Test
    void 未配置属性时沿用注解里的默认批大小与重试次数() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();

            context.getBean(OutboxRelay.class).relay();

            verify(jdbc).queryForList(anyString(), eq(Long.class), eq(10), any(), eq(100));
        });
    }

    @Test
    void 清理任务只删除七天前的已投递事件() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();

            context.getBean(OutboxRelay.class).cleanup();

            verify(jdbc).update(contains("DELETE FROM outbox_event"),
                    eq(UtcTime.toDb(NOW.minus(Duration.ofDays(7)))));
        });
    }
}
