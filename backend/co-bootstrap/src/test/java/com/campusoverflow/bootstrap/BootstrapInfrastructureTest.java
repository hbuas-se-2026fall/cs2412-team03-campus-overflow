package com.campusoverflow.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusoverflow.bootstrap.config.OpenApiConfig;
import com.campusoverflow.bootstrap.config.TimeConfig;
import com.campusoverflow.bootstrap.outbox.OutboxIntegrationEventPublisher;
import com.campusoverflow.bootstrap.platform.JdbcAuditTrail;
import com.campusoverflow.bootstrap.platform.JdbcProcessedEventStore;
import com.campusoverflow.shared.event.IntegrationEvent;
import com.campusoverflow.shared.util.UtcTime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** 启动装配层里不依赖 Spring 容器 / Docker 的部分：时钟、OpenAPI、审计、幂等守卫、事务性发件箱写端。 */
class BootstrapInfrastructureTest {

    private static final Instant NOW = Instant.parse("2026-11-05T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void applicationClockIsUtc() {
        assertThat(new TimeConfig().clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void openApiMetadataIsPublishedForFrontendCodegen() {
        var info = new OpenApiConfig().campusOverflowOpenApi().getInfo();

        assertThat(info.getTitle()).isEqualTo("CampusOverflow API");
        assertThat(info.getVersion()).isEqualTo("v1");
        assertThat(info.getDescription()).contains("RFC 9457 Problem Details");
        assertThat(info.getLicense().getName()).isEqualTo("仅供教学使用");
    }

    @Test
    void auditTrailWritesAppendOnlyRowAndTruncatesOversizedDetail() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcAuditTrail trail = new JdbcAuditTrail(jdbc, CLOCK);

        trail.record("LOGIN", 7L, "USER", "7", "short");
        trail.record("DELETE", 7L, "QUESTION", "10", "x".repeat(600));
        trail.record("LOGOUT", 7L, "USER", "7", null);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(3)).update(anyString(), captor.capture());
        Object[] first = captor.getAllValues().get(0);
        assertThat(first[0]).isEqualTo("LOGIN");
        assertThat(first[4]).isEqualTo("short");
        assertThat(first[5]).isEqualTo(UtcTime.toDb(NOW));
        assertThat((String) captor.getAllValues().get(1)[4]).hasSize(500);
        assertThat(captor.getAllValues().get(2)[4]).isNull();
    }

    @Test
    void processedEventStoreAdmitsOnlyTheFirstDelivery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        JdbcProcessedEventStore store = new JdbcProcessedEventStore(jdbc, CLOCK);

        assertThat(store.markProcessed("evt-1", "qa-consumer")).isTrue();
        assertThat(store.markProcessed("evt-1", "qa-consumer")).isFalse();

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(anyString(), captor.capture());
        assertThat(captor.getValue()[2]).isEqualTo(UtcTime.toDb(NOW));
    }

    @Test
    void outboxPublisherSerializesEventIntoOutboxRow() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenReturn("{\"eventId\":\"evt-1\"}");
        IntegrationEvent event = mock(IntegrationEvent.class);
        when(event.eventId()).thenReturn("evt-1");
        when(event.occurredAt()).thenReturn(NOW);

        new OutboxIntegrationEventPublisher(jdbc, mapper, CLOCK).publish(event);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), captor.capture());
        Object[] args = captor.getValue();
        assertThat(args[0]).isEqualTo("evt-1");
        assertThat(args[2]).isEqualTo("{\"eventId\":\"evt-1\"}");
        assertThat(args[3]).isEqualTo(UtcTime.toDb(NOW));
        assertThat(args[4]).isEqualTo(UtcTime.toDb(NOW));
    }

    @Test
    void outboxPublisherFailsLoudlyWhenEventCannotBeSerialized() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {
        });
        IntegrationEvent event = mock(IntegrationEvent.class);

        assertThatThrownBy(() -> new OutboxIntegrationEventPublisher(jdbc, mapper, CLOCK).publish(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("事件序列化失败");
    }
}
