package com.campusoverflow.reputation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.identity.api.event.UserRegisteredEvent;
import com.campusoverflow.qa.api.event.AnswerAcceptedEvent;
import com.campusoverflow.qa.api.event.AnswerEndorsedEvent;
import com.campusoverflow.qa.api.event.AnswerSubmittedEvent;
import com.campusoverflow.qa.api.event.VoteCastEvent;
import com.campusoverflow.reputation.application.fake.ReputationTestFixtures;
import com.campusoverflow.reputation.domain.BadgeType;
import com.campusoverflow.reputation.domain.LedgerEntry;
import com.campusoverflow.reputation.domain.ReputationReason;
import com.campusoverflow.shared.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 事件处理链：开户 → 采纳 +15 → 徽章 → 悬赏；投票增量与每日上限；提交/认证徽章。 */
class ReputationEventHandlersTest {

    private static final Instant NOW = Instant.parse("2026-11-11T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ReputationTestFixtures.Accounts accounts = new ReputationTestFixtures.Accounts();
    private final ReputationTestFixtures.Ledger ledger = new ReputationTestFixtures.Ledger();
    private final ReputationTestFixtures.Badges badges = new ReputationTestFixtures.Badges();
    private final ReputationTestFixtures.Bounties bounties = new ReputationTestFixtures.Bounties();
    private final ReputationTestFixtures.Events events = new ReputationTestFixtures.Events();
    private final ReputationTestFixtures.Audit audit = new ReputationTestFixtures.Audit();
    private final ReputationTestFixtures.Identity identity = new ReputationTestFixtures.Identity();
    private final ReputationTestFixtures.Qa qa = new ReputationTestFixtures.Qa();
    private final BadgeService badgeService = new BadgeService(badges, events, CLOCK);
    private final ReputationLedgerService ledgerService = new ReputationLedgerService(accounts, ledger, badgeService, events, CLOCK);
    private final BountyService bountyService = new BountyService(bounties, accounts, ledgerService, qa, identity, events, audit, CLOCK);
    private final ReputationEventHandlers handlers =
            new ReputationEventHandlers(accounts, ledger, ledgerService, badgeService, bountyService, CLOCK);

    private static UserRegisteredEvent registered(String eventId, long userId, Role role) {
        return new UserRegisteredEvent(eventId, NOW, userId, "用户" + userId, role);
    }

    private static AnswerAcceptedEvent accepted(String eventId, long answerAuthor, long questionAuthor) {
        return new AnswerAcceptedEvent(eventId, NOW, 50, 10, answerAuthor, questionAuthor, questionAuthor, "问题标题");
    }

    private static VoteCastEvent vote(String eventId, String targetType, long targetId, long authorId,
                                      int oldValue, int newValue) {
        return new VoteCastEvent(eventId, NOW, targetType, targetId, authorId, 99, 10, oldValue, newValue);
    }

    @Test
    void registrationOpensAccountsWithRoleBasedInitialPoints() {
        handlers.on(registered("e1", 7, Role.STUDENT));
        handlers.on(registered("e2", 8, Role.TEACHER));

        assertThat(accounts.get(7).reputation()).isZero();
        assertThat(accounts.get(7).availablePoints()).isZero();
        assertThat(accounts.get(8).availablePoints()).isEqualTo(1000);
    }

    @Test
    void duplicateRegistrationEventDoesNotResetExistingAccount() {
        accounts.given(7, 555, 30, 10);

        handlers.on(registered("e1", 7, Role.STUDENT));

        assertThat(accounts.get(7).reputation()).isEqualTo(555);
        assertThat(accounts.get(7).availablePoints()).isEqualTo(30);
    }

    @Test
    void acceptedAnswerGivesFifteenAndFirstAcceptedBadge() {
        handlers.on(registered("e1", 7, Role.STUDENT));

        handlers.on(accepted("e3", 7, 8));

        assertThat(accounts.get(7).reputation()).isEqualTo(15);
        assertThat(ledger.entries()).hasSize(1);
        assertThat(ledger.entries().get(0).reason()).isEqualTo(ReputationReason.ANSWER_ACCEPTED);
        assertThat(ledger.entries().get(0).refId()).isEqualTo(50);
        assertThat(badges.findByUser(7)).extracting(a -> a.type()).containsExactly(BadgeType.FIRST_ACCEPTED);
        assertThat(events.of(com.campusoverflow.reputation.api.event.ReputationChangedEvent.class)).hasSize(1);
    }

    @Test
    void selfAcceptedAnswerGivesNoReputation() {
        handlers.on(registered("e1", 7, Role.STUDENT));

        handlers.on(accepted("e3", 7, 7));

        assertThat(ledger.entries()).isEmpty();
        assertThat(accounts.get(7).reputation()).isZero();
        assertThat(badges.findByUser(7)).isEmpty();
    }

    @Test
    void upvoteAddsTenAndChangingVoteUsesDeltaOfEffects() {
        handlers.on(vote("v1", "ANSWER", 50, 7, 0, 1));
        assertThat(accounts.get(7).reputation()).isEqualTo(10);
        assertThat(ledger.entries().get(0).reason()).isEqualTo(ReputationReason.ANSWER_VOTE);

        handlers.on(vote("v2", "QUESTION", 10, 7, 0, 1));
        assertThat(accounts.get(7).reputation()).isEqualTo(20);

        // +1 改成 -1：增量是 -2 - 10 = -12
        handlers.on(vote("v3", "ANSWER", 50, 7, 1, -1));
        assertThat(accounts.get(7).reputation()).isEqualTo(8);
        assertThat(ledger.entries()).hasSize(3);
    }

    @Test
    void withdrawalOfVoteAlsoProducesDelta() {
        handlers.on(vote("v1", "ANSWER", 50, 7, 0, 1));
        handlers.on(vote("v2", "ANSWER", 50, 7, 1, 0));

        assertThat(accounts.get(7).reputation()).isZero();
        assertThat(ledger.entries()).hasSize(2);
    }

    @Test
    void dailyCapStopsFurtherPositiveVoteGain() {
        for (int i = 0; i < 20; i++) {
            ledger.append(LedgerEntry.of(7, "seed-" + i, ReputationReason.ANSWER_VOTE, 10, "ANSWER", 50, NOW));
        }

        handlers.on(vote("v1", "ANSWER", 50, 7, 0, 1));

        // 当天已达 200 上限 → 增量被压成 0 → 不写流水
        assertThat(ledger.entries()).hasSize(20);
        assertThat(accounts.findByUserId(7)).isEmpty();
    }

    @Test
    void submittingAnswerGrantsFirstAnswerBadgeOnce() {
        handlers.on(new AnswerSubmittedEvent("s1", NOW, 50, 10, 7, 8, "问题标题"));
        handlers.on(new AnswerSubmittedEvent("s2", NOW, 51, 10, 7, 8, "问题标题"));

        assertThat(badges.findByUser(7)).extracting(a -> a.type()).containsExactly(BadgeType.FIRST_ANSWER);
    }

    @Test
    void endorsementGrantsTeacherEndorsedBadge() {
        handlers.on(new AnswerEndorsedEvent("d1", NOW, 50, 10, 7, 9, "问题标题"));

        assertThat(badges.findByUser(7)).extracting(a -> a.type()).containsExactly(BadgeType.TEACHER_ENDORSED);
    }
}
