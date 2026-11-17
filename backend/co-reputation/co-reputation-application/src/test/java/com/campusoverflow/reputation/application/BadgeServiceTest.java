package com.campusoverflow.reputation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.reputation.api.event.BadgeAwardedEvent;
import com.campusoverflow.reputation.application.fake.ReputationTestFixtures;
import com.campusoverflow.reputation.domain.BadgeType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BadgeServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private final ReputationTestFixtures.Badges badges = new ReputationTestFixtures.Badges();
    private final ReputationTestFixtures.Events events = new ReputationTestFixtures.Events();
    private final BadgeService service = new BadgeService(badges, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void grantsOnceAndPublishesBadgeAwardedEvent() {
        service.grant(7, BadgeType.FIRST_ACCEPTED);

        assertThat(badges.awards()).hasSize(1);
        assertThat(badges.awards().get(0).type()).isEqualTo(BadgeType.FIRST_ACCEPTED);
        assertThat(badges.awards().get(0).awardedAt()).isEqualTo(NOW);

        BadgeAwardedEvent event = events.of(BadgeAwardedEvent.class).get(0);
        assertThat(event.userId()).isEqualTo(7);
        assertThat(event.badgeCode()).isEqualTo("FIRST_ACCEPTED");
        assertThat(event.badgeName()).isEqualTo(BadgeType.FIRST_ACCEPTED.displayName());
    }

    @Test
    void secondGrantIsIgnoredSoAtLeastOnceDeliveryStaysIdempotent() {
        service.grant(7, BadgeType.FIRST_ANSWER);
        service.grant(7, BadgeType.FIRST_ANSWER);

        assertThat(badges.awards()).hasSize(1);
        assertThat(events.published()).hasSize(1);
    }

    @Test
    void differentUsersAndTypesAreIndependent() {
        service.grant(7, BadgeType.FIRST_ANSWER);
        service.grant(8, BadgeType.FIRST_ANSWER);
        service.grant(7, BadgeType.TEACHER_ENDORSED);

        assertThat(badges.awards()).hasSize(3);
    }

    @Test
    void nullTypeIsRejectedInsteadOfSilentlyIgnored() {
        assertThatThrownBy(() -> service.grant(7, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("徽章类型");
    }
}
