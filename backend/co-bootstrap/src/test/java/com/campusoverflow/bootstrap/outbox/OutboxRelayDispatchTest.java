package com.campusoverflow.bootstrap.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusoverflow.identity.api.event.UserRegisteredEvent;
import com.campusoverflow.shared.security.Role;
import com.campusoverflow.shared.util.UtcTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 发件箱中继的投递契约：认领 → 反序列化 → 投递 → 标记；失败时指数退避并释放后续行租约，
 * 保证「至少一次」且批内顺序不被越过。
 *
 * <p>{@code OutboxRelay.Row} 是私有的内部 record，测试用反射构造它，避免为了可测性放宽生产代码的可见性。
 * 这里断言的都是行为（事件内容、SQL 参数、退避时长、租约释放），不是 getter 或行数。
 */
class OutboxRelayDispatchTest {

    private static final Instant NOW = Instant.parse("2026-11-11T02:00:00Z");
    private static final String USER_EVENT = "com.campusoverflow.identity.api.event.UserRegisteredEvent";
    private static final String VALID_PAYLOAD = """
            {"eventId":"evt-1","occurredAt":"2026-11-11T02:00:00Z","userId":7,
             "displayName":"小明","role":"STUDENT"}
            """;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final ApplicationEventPublisher bus = mock(ApplicationEventPublisher.class);

    private final OutboxRelay relay = new OutboxRelay(jdbc, named, tx, bus,
            new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC),
            new SimpleMeterRegistry(), 100, 10);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void transactionTemplateExecutesItsCallback() {
        when(tx.execute(any())).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    /** Row 是 OutboxRelay 的私有 record，只能通过反射按 canonical constructor 构造。 */
    private static Object row(long id, String eventId, String type, String payload) {
        try {
            Class<?> rowClass = Class.forName("com.campusoverflow.bootstrap.outbox.OutboxRelay$Row");
            Constructor<?> ctor = rowClass.getDeclaredConstructor(long.class, String.class, String.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(id, eventId, type, payload);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法构造 OutboxRelay.Row", e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void claimReturns(List<Long> ids, Object... rows) {
        when(jdbc.queryForList(anyString(), eq(Long.class), any(), any(), any())).thenReturn(ids);
        when(named.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> List.of(rows));
    }

    @Test
    void 认领到的事件被反序列化并投递后标记为已发布() {
        claimReturns(List.of(1L), row(1L, "evt-1", USER_EVENT, VALID_PAYLOAD));

        relay.relay();

        var event = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(bus).publishEvent(event.capture());
        assertThat(event.getValue())
                .isInstanceOfSatisfying(UserRegisteredEvent.class, e -> {
                    assertThat(e.eventId()).isEqualTo("evt-1");
                    assertThat(e.userId()).isEqualTo(7L);
                    assertThat(e.role()).isEqualTo(Role.STUDENT);
                    assertThat(e.occurredAt()).isEqualTo(NOW);
                });
        verify(jdbc).update(contains("SET published_at = ?"), eq(UtcTime.toDb(NOW)), eq(1L));
    }

    @Test
    void 非法事件类型被拒绝且不会进入事件总线() {
        // java.lang.String 真实存在但不是 IntegrationEvent，走的是防御反序列化攻击的分支
        claimReturns(List.of(1L), row(1L, "evt-1", "java.lang.String", "{}"));

        relay.relay();

        verify(bus, never()).publishEvent(any());
        verify(jdbc).update(contains("SET attempts = ?"), eq(1),
                eq(UtcTime.toDb(NOW.plusSeconds(2))), contains("非法事件类型"), eq(1L));
    }

    @Test
    void 投递失败时按指数退避重试并释放后续行的租约() {
        claimReturns(List.of(1L, 2L),
                row(1L, "evt-1", "com.example.DoesNotExist", "{}"),
                row(2L, "evt-2", USER_EVENT, VALID_PAYLOAD));
        when(jdbc.queryForObject(contains("SELECT attempts"), eq(Integer.class), eq(1L))).thenReturn(2);

        relay.relay();

        // 失败后本批立即停止：第二行不得被投递，也不得越过失败事件
        verify(bus, never()).publishEvent(any());
        // attempts 2 -> 3，退避 min(1 << 3, 300) = 8 秒
        verify(jdbc).update(contains("SET attempts = ?"), eq(3),
                eq(UtcTime.toDb(NOW.plusSeconds(8))), anyString(), eq(1L));

        var released = org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).update(contains("locked_until = NULL WHERE id IN (:ids)"), released.capture());
        assertThat(((MapSqlParameterSource) released.getValue()).getValues()).containsKey("ids");
    }

    @Test
    void 空批次不会写入任何更新() {
        when(jdbc.queryForList(anyString(), eq(Long.class), any(), any(), any())).thenReturn(List.of());

        relay.relay();

        verify(bus, never()).publishEvent(any());
        verify(named, never()).update(anyString(), any(SqlParameterSource.class));
        verify(jdbc, never()).update(anyString(), any(), any());
    }
}
