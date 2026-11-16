package com.campusoverflow.reputation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.ConflictException;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 悬赏聚合的完整状态机：OPEN → AWARDED / EXPIRED，以及每个转移入口的守卫。
 * 之前只有 36/53 行被覆盖，主要是「非发起人不能拿走自己的悬赏」「已结束不能再操作」「到期判定边界」这几条没测。
 */
class BountyStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private static final Actor TEACHER = new Actor(9, "王老师", Role.TEACHER);

    private static Bounty open(int points, int days) {
        return Bounty.open(42L, true, TEACHER, true, points, days, NOW);
    }

    @Test
    void 发起悬赏后处于进行中并按天数设置到期时间() {
        Bounty bounty = open(100, 7);

        assertThat(bounty.id()).isNull();
        assertThat(bounty.status()).isEqualTo(BountyStatus.OPEN);
        assertThat(bounty.questionId()).isEqualTo(42L);
        assertThat(bounty.sponsorId()).isEqualTo(TEACHER.userId());
        assertThat(bounty.points()).isEqualTo(100);
        assertThat(bounty.createdAt()).isEqualTo(NOW);
        assertThat(bounty.expiresAt()).isEqualTo(NOW.plusSeconds(7 * 24 * 3600));
        assertThat(bounty.winnerId()).isNull();
        assertThat(bounty.closedAt()).isNull();
    }

    @Test
    void 只有课程教师或助教能发起悬赏() {
        assertThatThrownBy(() -> Bounty.open(42L, true, new Actor(1, "小明", Role.STUDENT), false, 100, 7, NOW))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("REP-3001");
    }

    @Test
    void 已删除或已采纳的问题不能发起悬赏() {
        assertThatThrownBy(() -> Bounty.open(42L, false, TEACHER, true, 100, 7, NOW))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("REP-1008");
    }

    @Test
    void 积分必须在十到五百之间() {
        assertThatThrownBy(() -> open(9, 7))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("REP-1003");
        assertThatThrownBy(() -> open(501, 7))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("REP-1003");
        assertThat(open(10, 7).points()).isEqualTo(10);
        assertThat(open(500, 7).points()).isEqualTo(500);
    }

    @Test
    void 期限必须在一到十四天之间() {
        assertThatThrownBy(() -> open(100, 0))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("REP-1004");
        assertThatThrownBy(() -> open(100, 15))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("REP-1004");
        assertThat(open(100, 14).expiresAt()).isEqualTo(NOW.plusSeconds(14 * 24 * 3600));
    }

    @Test
    void 发放悬赏记录获胜者与回答并关闭() {
        Bounty bounty = open(100, 7);

        bounty.award(7L, 200L, NOW.plusSeconds(3600));

        assertThat(bounty.status()).isEqualTo(BountyStatus.AWARDED);
        assertThat(bounty.answerId()).isEqualTo(7L);
        assertThat(bounty.winnerId()).isEqualTo(200L);
        assertThat(bounty.closedAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void 发起人不能拿走自己发起的悬赏() {
        Bounty bounty = open(100, 7);

        assertThatThrownBy(() -> bounty.award(7L, TEACHER.userId(), NOW))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("REP-1005");
        assertThat(bounty.status()).isEqualTo(BountyStatus.OPEN);
    }

    @Test
    void 已发放的悬赏不能再发放或关闭() {
        Bounty bounty = open(100, 7);
        bounty.award(7L, 200L, NOW);

        assertThatThrownBy(() -> bounty.award(8L, 201L, NOW))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("REP-2001");
        assertThatThrownBy(() -> bounty.close(NOW))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 到期关闭把状态改为过期() {
        Bounty bounty = open(100, 7);

        bounty.close(NOW.plusSeconds(7 * 24 * 3600));

        assertThat(bounty.status()).isEqualTo(BountyStatus.EXPIRED);
        assertThat(bounty.closedAt()).isEqualTo(NOW.plusSeconds(7 * 24 * 3600));
        assertThat(bounty.winnerId()).isNull();
    }

    @Test
    void 已过期的悬赏不能再发放() {
        Bounty bounty = open(100, 7);
        bounty.close(NOW);

        assertThatThrownBy(() -> bounty.award(7L, 200L, NOW))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("REP-2001");
    }

    @Test
    void 到期判定在到期时刻即算到期() {
        Bounty bounty = open(100, 1);

        assertThat(bounty.isDue(NOW.minusSeconds(1))).isFalse();
        assertThat(bounty.isDue(NOW)).isFalse();
        assertThat(bounty.isDue(NOW.plusSeconds(24 * 3600 - 1))).isFalse();
        assertThat(bounty.isDue(NOW.plusSeconds(24 * 3600))).isTrue();
        assertThat(bounty.isDue(NOW.plusSeconds(24 * 3600 + 1))).isTrue();
    }

    @Test
    void 主键只能回填一次() {
        Bounty bounty = open(100, 7);

        bounty.assignId(1L);
        assertThat(bounty.id()).isEqualTo(1L);
        assertThatThrownBy(() -> bounty.assignId(2L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 重建悬赏保留全部字段() {
        Instant closed = NOW.plusSeconds(3600);
        Bounty bounty = Bounty.reconstitute(5L, 42L, TEACHER.userId(), 100, BountyStatus.AWARDED,
                NOW, NOW.plusSeconds(7 * 24 * 3600), 200L, 7L, closed);

        assertThat(bounty.id()).isEqualTo(5L);
        assertThat(bounty.status()).isEqualTo(BountyStatus.AWARDED);
        assertThat(bounty.winnerId()).isEqualTo(200L);
        assertThat(bounty.answerId()).isEqualTo(7L);
        assertThat(bounty.closedAt()).isEqualTo(closed);
    }
}
