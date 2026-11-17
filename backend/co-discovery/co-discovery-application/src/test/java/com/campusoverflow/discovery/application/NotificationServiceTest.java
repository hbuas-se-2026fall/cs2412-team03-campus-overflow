package com.campusoverflow.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.discovery.domain.notification.Notification;
import com.campusoverflow.discovery.domain.notification.NotificationType;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private static Actor actor(long id) {
        return new Actor(id, "用户" + id, Role.STUDENT);
    }

    private static Notification note(long id, long recipientId, String eventId) {
        return Notification.reconstitute(id, recipientId, NotificationType.ANSWER_ACCEPTED,
                "你的回答被采纳", "/questions/1", eventId, false, NOW);
    }

    private static NotificationService service(DiscoveryFakes.Notifications repo) {
        return new NotificationService(repo);
    }

    @Test
    void listOnlyReturnsCurrentActorsNotifications() {
        var repo = new DiscoveryFakes.Notifications();
        repo.save(note(1, 7, "e1"));
        repo.save(note(2, 8, "e2"));
        repo.save(note(3, 7, "e3"));

        PageResult<NotificationView> result = service(repo).list(actor(7), new PageRequest(1, 10));

        assertThat(result.items()).hasSize(2);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).allSatisfy(v -> {
            assertThat(v.type()).isEqualTo("ANSWER_ACCEPTED");
            assertThat(v.link()).isEqualTo("/questions/1");
            assertThat(v.read()).isFalse();
            assertThat(v.createdAt()).isEqualTo(NOW);
        });
    }

    @Test
    void unreadCountAndMarkAllReadOperateOnActorScopeOnly() {
        var repo = new DiscoveryFakes.Notifications();
        repo.save(note(1, 7, "e1"));
        repo.save(note(2, 7, "e2"));
        repo.save(note(3, 8, "e3"));
        var service = service(repo);

        assertThat(service.unreadCount(actor(7))).isEqualTo(2);
        assertThat(service.markAllRead(actor(7))).isEqualTo(2);
        assertThat(service.unreadCount(actor(7))).isZero();
        assertThat(service.unreadCount(actor(8))).isEqualTo(1);
    }

    @Test
    void markReadRejectsAnotherUsersNotification() {
        var repo = new DiscoveryFakes.Notifications();
        repo.save(note(1, 7, "e1"));

        assertThatThrownBy(() -> service(repo).markRead(actor(8), 1))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(e -> assertThat(((ForbiddenException) e).code()).isEqualTo("DIS-3001"));
        assertThat(repo.saved.get(0).read()).isFalse();
    }

    @Test
    void markReadOfMissingNotificationReturnsNotFoundCode() {
        var repo = new DiscoveryFakes.Notifications();

        assertThatThrownBy(() -> service(repo).markRead(actor(7), 404))
                .isInstanceOf(NotFoundException.class)
                .satisfies(e -> assertThat(((NotFoundException) e).code()).isEqualTo("DIS-3002"));
    }

    @Test
    void markReadIsIdempotentAndDoesNotDuplicateTheRow() {
        var repo = new DiscoveryFakes.Notifications();
        repo.save(note(1, 7, "e1"));
        var service = service(repo);

        service.markRead(actor(7), 1);
        service.markRead(actor(7), 1);

        assertThat(repo.saved).hasSize(1);
        assertThat(repo.saved.get(0).read()).isTrue();
        assertThat(service.unreadCount(actor(7))).isZero();
    }
}
