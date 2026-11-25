package com.campusoverflow.bootstrap.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code OutboxRelay} 在**异常路径**与**指标**上的行为。
 *
 * <p>与 {@link OutboxRelayDispatchTest} 分工：那边测「正常投递 + 一次失败退避」的契约，
 * 这边补三块之前没测到的：
 * <ol>
 *   <li>{@link OutboxRelay#cleanup()} —— 保留 7 天的清理任务，删错时间窗会误删未投递事件，必须钉死边界；</li>
 *   <li>错误信息**截断到 500 字** —— 否则一条超长堆栈会把 last_error 列撑爆；</li>
 *   <li>{@code count()} 的**降级语义** —— 指标查询炸了返回 NaN，不能让整个中继停摆（可观测性不能反噬主流程）。</li>
 * </ol>
 */
class OutboxRelayLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-11-11T02:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final ApplicationEventPublisher bus = mock(ApplicationEventPublisher.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();

    private final OutboxRelay relay = new OutboxRelay(jdbc, named, tx, bus,
            new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC),
            registry, 100, 10);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void transactionTemplateExecutesItsCallback() {
        when(tx.execute(any())).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private static Object row(long id, String type, String payload) {
        try {
            Class<?> rowClass = Class.forName("com.campusoverflow.bootstrap.outbox.OutboxRelay$Row");
            Constructor<?> ctor = rowClass.getDeclaredConstructor(long.class, String.class, String.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(id, "evt-" + id, type, payload);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法构造 OutboxRelay.Row", e);
        }
    }

    // ---------- cleanup ----------

    @Test
    void 清理只删七天前已投递的事件() {
        // 关键是时间窗：published_at 的截止点必须是「当前时间 - 7 天」，
        // 写成 minus(7 天) 的相反数、或按 createdAt 算，都会把合规期内的事件删掉。
        when(jdbc.update(contains("DELETE FROM outbox_event"))).thenReturn(3);

        relay.cleanup();

        var captured = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(jdbc).update(contains("DELETE FROM outbox_event"), captured.capture());
        assertThat(captured.getValue())
                .as("截止点 = 当前时间 - 7 天（转成数据库时区）")
                .isEqualTo(com.campusoverflow.shared.util.UtcTime.toDb(NOW.minus(Duration.ofDays(7))));
    }

    @Test
    void 没有可清理事件时也不报错() {
        when(jdbc.update(contains("DELETE FROM outbox_event"))).thenReturn(0);

        assertThatCode(relay::cleanup).doesNotThrowAnyException();
    }

    // ---------- 错误信息截断 ----------

    @Test
    void 超长的失败原因被截断到五百字以内() {
        // payload 是 2000 字的非法 JSON：Jackson 的报错会把原文回显进 message，
        // 若不截断，last_error 列（VARCHAR(500)）会直接抛 SQL 异常，把一次投递失败升级成中继崩溃。
        String huge = "x".repeat(2000);
        when(jdbc.queryForList(anyString(), eq(Long.class), any(), any(), any())).thenReturn(List.of(1L));
        when(named.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> List.of(row(1L, "com.campusoverflow.identity.api.event.UserRegisteredEvent", huge)));
        when(jdbc.queryForObject(contains("SELECT attempts"), eq(Integer.class), eq(1L))).thenReturn(0);

        relay.relay();

        var error = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).update(contains("SET attempts = ?"),
                eq(1), any(), error.capture(), eq(1L));
        assertThat(error.getValue()).hasSizeLessThanOrEqualTo(500);
    }

    @Test
    void 短失败原因原样写入便于排查() {
        // 用「类存在但不是 IntegrationEvent」触发防御分支：它抛出的 IllegalStateException
        // 的 message 只带类型名（"非法事件类型: com.example.Nope" 里的中文前缀在异常 message 中，
        // 但 String.valueOf(e.getMessage()) 拿到的是完整 message —— 这里断言实际写下去的原文）
        when(jdbc.queryForList(anyString(), eq(Long.class), any(), any(), any())).thenReturn(List.of(1L));
        when(named.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> List.of(row(1L, "java.lang.String", "{}")));
        when(jdbc.queryForObject(contains("SELECT attempts"), eq(Integer.class), eq(1L))).thenReturn(0);

        relay.relay();

        var error = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).update(contains("SET attempts = ?"),
                eq(1), any(), error.capture(), eq(1L));
        // 短消息不做任何改写，原样落库（便于排查），且必须带上出事的类型名
        assertThat(error.getValue()).isEqualTo("非法事件类型: java.lang.String");
    }

    // ---------- 指标降级 ----------

    @Test
    void 积压指标在数据库故障时返回NaN而不是抛异常() {
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new DataAccessResourceFailureException("数据库连接中断"));

        Gauge backlog = registry.find("co_outbox_backlog").gauge();
        Gauge deadLetters = registry.find("co_outbox_dead_letters").gauge();

        assertThat(backlog).as("两个 gauge 都已在构造器里注册").isNotNull();
        assertThat(deadLetters).isNotNull();
        assertThat(backlog.value())
                .as("指标查询失败应降级为 NaN，绝不能把异常抛给 Prometheus 抓取路径")
                .isNaN();
    }

    @Test
    void 数据库可用时积压指标反映真实计数() {
        when(jdbc.queryForObject(contains("attempts < 10"), eq(Long.class))).thenReturn(4L);
        when(jdbc.queryForObject(contains("attempts >= 10"), eq(Long.class))).thenReturn(2L);

        assertThat(registry.find("co_outbox_backlog").gauge().value()).isEqualTo(4.0);
        assertThat(registry.find("co_outbox_dead_letters").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void 计数查询返回空值按零处理() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);

        assertThat(registry.find("co_outbox_backlog").gauge().value()).isZero();
    }
}
