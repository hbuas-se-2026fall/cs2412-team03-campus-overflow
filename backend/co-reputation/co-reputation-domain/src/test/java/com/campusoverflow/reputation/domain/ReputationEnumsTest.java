package com.campusoverflow.reputation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 枚举元数据：徽章文案与声誉原因文案会直接出现在 API 响应与前端展示里，写错就是用户看到错字。 */
class ReputationEnumsTest {

    @Test
    void 六种徽章都有中文名与说明() {
        assertThat(BadgeType.values()).hasSize(6);
        for (BadgeType type : BadgeType.values()) {
            assertThat(type.displayName()).isNotBlank();
            assertThat(type.description()).isNotBlank();
        }
    }

    @Test
    void 关键徽章文案固定() {
        assertThat(BadgeType.FIRST_ACCEPTED.displayName()).isEqualTo("初露锋芒");
        assertThat(BadgeType.FIRST_ACCEPTED.description()).isEqualTo("第一个回答被采纳");
        assertThat(BadgeType.HELPER_10.displayName()).isEqualTo("解惑达人");
        assertThat(BadgeType.TEACHER_ENDORSED.displayName()).isEqualTo("名师认可");
        assertThat(BadgeType.REPUTATION_1000.displayName()).isEqualTo("德高望重");
    }

    @Test
    void 四种声誉原因都有中文标签() {
        assertThat(ReputationReason.values()).hasSize(4);
        for (ReputationReason reason : ReputationReason.values()) {
            assertThat(reason.label()).isNotBlank();
        }
        assertThat(ReputationReason.ANSWER_ACCEPTED.label()).isEqualTo("回答被采纳");
        assertThat(ReputationReason.BOUNTY_AWARDED.label()).isEqualTo("赢得悬赏");
    }

    @Test
    void 只有投票类原因算投票() {
        assertThat(ReputationReason.QUESTION_VOTE.isVote()).isTrue();
        assertThat(ReputationReason.ANSWER_VOTE.isVote()).isTrue();
        assertThat(ReputationReason.ANSWER_ACCEPTED.isVote()).isFalse();
        assertThat(ReputationReason.BOUNTY_AWARDED.isVote()).isFalse();
    }

    @Test
    void 悬赏状态三态固定() {
        assertThat(BountyStatus.values()).containsExactly(BountyStatus.OPEN, BountyStatus.AWARDED, BountyStatus.EXPIRED);
    }
}
