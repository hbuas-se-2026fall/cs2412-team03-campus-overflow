package com.campusoverflow.reputation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.shared.domain.BusinessRuleException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 收尾两处：投票值到声誉效应的映射（含非法值），以及徽章授予记录本身。 */
class ReputationEdgeTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

    @Test
    void 三种投票值的声誉效应固定() {
        assertThat(ReputationRulebook.voteEffect(1)).isEqualTo(ReputationRulebook.UPVOTE);
        assertThat(ReputationRulebook.voteEffect(-1)).isEqualTo(ReputationRulebook.DOWNVOTE);
        assertThat(ReputationRulebook.voteEffect(0)).isZero();
    }

    @Test
    void 非法投票值被拒绝而不是静默当成零() {
        assertThatThrownBy(() -> ReputationRulebook.voteEffect(2))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("+1、-1 或 0")
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("REP-1001");
        assertThatThrownBy(() -> ReputationRulebook.voteEffect(-5))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void 徽章授予记录保存用户徽章与时间() {
        BadgeAward award = new BadgeAward(9L, BadgeType.FIRST_ACCEPTED, NOW);

        assertThat(award.userId()).isEqualTo(9L);
        assertThat(award.type()).isEqualTo(BadgeType.FIRST_ACCEPTED);
        assertThat(award.awardedAt()).isEqualTo(NOW);
    }
}
