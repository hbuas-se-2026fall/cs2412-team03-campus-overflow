package com.campusoverflow.reputation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 徽章阈值：采纳数与声誉两个维度，边界值（0/1/9/10、99/100/999/1000）决定什么时候发徽章。 */
class BadgePolicyTest {

    @Test
    void 没有回答被采纳时没有徽章() {
        assertThat(BadgePolicy.forAcceptedCount(0)).isEmpty();
    }

    @Test
    void 第一个回答被采纳获得初露锋芒() {
        assertThat(BadgePolicy.forAcceptedCount(1)).containsExactly(BadgeType.FIRST_ACCEPTED);
        assertThat(BadgePolicy.forAcceptedCount(9)).containsExactly(BadgeType.FIRST_ACCEPTED);
    }

    @Test
    void 累计十个回答被采纳再获得解惑达人() {
        assertThat(BadgePolicy.forAcceptedCount(10))
                .containsExactly(BadgeType.FIRST_ACCEPTED, BadgeType.HELPER_10);
        assertThat(BadgePolicy.forAcceptedCount(99))
                .containsExactly(BadgeType.FIRST_ACCEPTED, BadgeType.HELPER_10);
    }

    @Test
    void 声誉未到一百没有徽章() {
        assertThat(BadgePolicy.forReputation(0)).isEmpty();
        assertThat(BadgePolicy.forReputation(99)).isEmpty();
    }

    @Test
    void 声誉到一百获得小有名气() {
        assertThat(BadgePolicy.forReputation(100)).containsExactly(BadgeType.REPUTATION_100);
        assertThat(BadgePolicy.forReputation(999)).containsExactly(BadgeType.REPUTATION_100);
    }

    @Test
    void 声誉到一千再获得德高望重() {
        assertThat(BadgePolicy.forReputation(1000))
                .containsExactly(BadgeType.REPUTATION_100, BadgeType.REPUTATION_1000);
    }

    @Test
    void 负数输入不会越界() {
        assertThat(BadgePolicy.forAcceptedCount(-1)).isEmpty();
        assertThat(BadgePolicy.forReputation(-500)).isEmpty();
    }
}
