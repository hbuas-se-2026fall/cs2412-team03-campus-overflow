package com.campusoverflow.discovery.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.shared.domain.ForbiddenException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 通知聚合：创建时的默认值、主键回填、已读权限与幂等。 */
class NotificationTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

    private static Notification notification(long recipientId) {
        return Notification.create(recipientId, NotificationType.ANSWER_RECEIVED, "你的问题收到新回答",
                "/questions/1", "evt-1", NOW);
    }

    @Test
    void 新建通知默认未读且没有主键() {
        Notification n = notification(7L);

        assertThat(n.id()).isNull();
        assertThat(n.read()).isFalse();
        assertThat(n.recipientId()).isEqualTo(7L);
        assertThat(n.type()).isEqualTo(NotificationType.ANSWER_RECEIVED);
        assertThat(n.title()).isEqualTo("你的问题收到新回答");
        assertThat(n.link()).isEqualTo("/questions/1");
        assertThat(n.sourceEventId()).isEqualTo("evt-1");
        assertThat(n.createdAt()).isEqualTo(NOW);
    }

    @Test
    void 类型与来源事件不能为空() {
        assertThatThrownBy(() -> Notification.create(7L, null, "标题", "/x", "evt-1", NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Notification.create(7L, NotificationType.MENTIONED, "标题", "/x", null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 主键由持久化层回填() {
        Notification n = notification(7L);

        n.assignId(42L);

        assertThat(n.id()).isEqualTo(42L);
    }

    @Test
    void 只有收件人本人能把通知标记为已读() {
        Notification n = notification(7L);

        assertThatThrownBy(() -> n.markRead(8L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("只能标记自己的通知")
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("DIS-3001");
        assertThat(n.read()).isFalse();
    }

    @Test
    void 重复标记已读不会抛错也不会改变状态() {
        Notification n = notification(7L);

        n.markRead(7L);
        n.markRead(7L);

        assertThat(n.read()).isTrue();
    }

    @Test
    void 从持久化重建时保留已读状态() {
        Notification n = Notification.reconstitute(42L, 7L, NotificationType.BADGE_AWARDED, "获得徽章",
                "/users/7/reputation", "evt-2", true, NOW);

        assertThat(n.id()).isEqualTo(42L);
        assertThat(n.read()).isTrue();
        assertThat(n.type()).isEqualTo(NotificationType.BADGE_AWARDED);
    }

    @Test
    void 通知类型覆盖六类业务事件() {
        assertThat(NotificationType.values()).containsExactly(
                NotificationType.ANSWER_RECEIVED,
                NotificationType.ANSWER_ACCEPTED,
                NotificationType.ANSWER_ENDORSED,
                NotificationType.MENTIONED,
                NotificationType.BOUNTY_AWARDED,
                NotificationType.BADGE_AWARDED);
    }
}
