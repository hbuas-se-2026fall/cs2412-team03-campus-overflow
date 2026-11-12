package com.campusoverflow.reputation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.reputation.api.event.ReputationChangedEvent;
import com.campusoverflow.reputation.application.fake.ReputationTestFixtures;
import com.campusoverflow.reputation.domain.BadgeType;
import com.campusoverflow.reputation.domain.ReputationReason;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 记账核心：余额 = 流水之和，且对重复事件幂等。 */
class ReputationLedgerServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ReputationTestFixtures.Accounts accounts = new ReputationTestFixtures.Accounts();
    private final ReputationTestFixtures.Ledger ledger = new ReputationTestFixtures.Ledger();
    private final ReputationTestFixtures.Badges badges = new ReputationTestFixtures.Badges();
    private final ReputationTestFixtures.Events events = new ReputationTestFixtures.Events();
    private final ReputationLedgerService service =
            new ReputationLedgerService(accounts, ledger, new BadgeService(badges, events, CLOCK), events, CLOCK);

    @Test
    void recordsDeltaOpensAccountIfMissingAndPublishesChangedEvent() {
        boolean recorded = service.record(7, "e1", ReputationReason.ANSWER_ACCEPTED, 15, "ANSWER", 50);

        assertThat(recorded).isTrue();
        assertThat(accounts.get(7).reputation()).isEqualTo(15);
        assertThat(ledger.entries()).hasSize(1);
        assertThat(ledger.entries().get(0).delta()).isEqualTo(15);
        assertThat(ledger.entries().get(0).createdAt()).isEqualTo(NOW);

        ReputationChangedEvent event = events.of(ReputationChangedEvent.class).get(0);
        assertThat(event.userId()).isEqualTo(7);
        assertThat(event.delta()).isEqualTo(15);
        assertThat(event.reputation()).isEqualTo(15);
        assertThat(event.reason()).isEqualTo("ANSWER_ACCEPTED");
    }

    @Test
    void duplicateEventIsIgnoredSoLedgerCannotDoubleCount() {
        service.record(7, "e1", ReputationReason.BOUNTY_AWARDED, 50, "BOUNTY", 9);
        boolean second = service.record(7, "e1", ReputationReason.BOUNTY_AWARDED, 50, "BOUNTY", 9);

        assertThat(second).isFalse();
        assertThat(accounts.get(7).reputation()).isEqualTo(50);
        assertThat(ledger.entries()).hasSize(1);
    }

    @Test
    void zeroDeltaIsNotRecordedAtAll() {
        boolean recorded = service.record(7, "e2", ReputationReason.ANSWER_ACCEPTED, 0, "ANSWER", 50);

        assertThat(recorded).isFalse();
        assertThat(ledger.entries()).isEmpty();
        assertThat(accounts.findByUserId(7)).isEmpty();
    }

    @Test
    void crossingReputationThresholdGrantsBadgeInSameRecord() {
        accounts.given(7, 90, 0, 0);

        service.record(7, "e3", ReputationReason.ANSWER_ACCEPTED, 15, "ANSWER", 50);

        assertThat(accounts.get(7).reputation()).isEqualTo(105);
        assertThat(badges.findByUser(7)).extracting(a -> a.type()).containsExactly(BadgeType.REPUTATION_100);
        assertThat(events.of(com.campusoverflow.reputation.api.event.BadgeAwardedEvent.class)).hasSize(1);
    }
}
