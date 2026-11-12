package com.campusoverflow.reputation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.reputation.api.event.BountyAwardedEvent;
import com.campusoverflow.reputation.api.event.BountyClosedEvent;
import com.campusoverflow.reputation.api.event.BountyOpenedEvent;
import com.campusoverflow.reputation.application.fake.ReputationTestFixtures;
import com.campusoverflow.reputation.domain.Bounty;
import com.campusoverflow.reputation.domain.BountyStatus;
import com.campusoverflow.reputation.domain.ReputationReason;
import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.ConflictException;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 悬赏：发起要冻结积分、采纳要结算、到期要退款，且每个入口的异常码都要对得上文档。 */
class BountyServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    private final ReputationTestFixtures.Accounts accounts = new ReputationTestFixtures.Accounts();
    private final ReputationTestFixtures.Ledger ledger = new ReputationTestFixtures.Ledger();
    private final ReputationTestFixtures.Badges badges = new ReputationTestFixtures.Badges();
    private final ReputationTestFixtures.Bounties bounties = new ReputationTestFixtures.Bounties();
    private final ReputationTestFixtures.Events events = new ReputationTestFixtures.Events();
    private final ReputationTestFixtures.Audit audit = new ReputationTestFixtures.Audit();
    private final ReputationTestFixtures.Identity identity = new ReputationTestFixtures.Identity();
    private final ReputationTestFixtures.Qa qa = new ReputationTestFixtures.Qa();
    private final BadgeService badgeService = new BadgeService(badges, events, Clock.fixed(NOW, ZoneOffset.UTC));
    private final ReputationLedgerService ledgerService =
            new ReputationLedgerService(accounts, ledger, badgeService, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private BountyService serviceAt(Instant now) {
        return new BountyService(bounties, accounts, ledgerService, qa, identity, events, audit,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private final BountyService service = serviceAt(NOW);

    private static Actor teacher() {
        return new Actor(9, "王老师", Role.TEACHER);
    }

    private void givenOpenQuestion() {
        qa.given(10, 5, 3L, "跳表和红黑树在工程上如何取舍？", null, false);
        identity.givenCourseManager(9);
        accounts.given(9, 0, 1000, 0);
    }

    @Test
    void openFreezesPointsPublishesEventAndAudits() {
        givenOpenQuestion();

        long id = service.open(teacher(), 10, 50, 7);

        assertThat(id).isPositive();
        assertThat(accounts.get(9).availablePoints()).isEqualTo(950);
        assertThat(accounts.get(9).frozenPoints()).isEqualTo(50);
        assertThat(bounties.findOpenByQuestion(10)).isPresent();
        assertThat(events.of(BountyOpenedEvent.class).get(0).points()).isEqualTo(50);
        assertThat(audit.records()).containsExactly("BOUNTY_OPENED|9|QUESTION|10");
    }

    @Test
    void openRejectsMissingQuestionWithRep4001() {
        assertThatThrownBy(() -> service.open(teacher(), 404, 50, 7))
                .isInstanceOf(NotFoundException.class)
                .satisfies(e -> assertThat(((NotFoundException) e).code()).isEqualTo("REP-4001"));
    }

    @Test
    void openRejectsSecondOpenBountyWithRep2002() {
        givenOpenQuestion();
        service.open(teacher(), 10, 50, 7);

        assertThatThrownBy(() -> service.open(teacher(), 10, 20, 3))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).code()).isEqualTo("REP-2002"));
    }

    @Test
    void openRejectsNonManagerWithRep3001() {
        qa.given(10, 5, 3L, "跳表和红黑树在工程上如何取舍？", null, false);
        accounts.given(9, 0, 1000, 0);

        assertThatThrownBy(() -> service.open(teacher(), 10, 50, 7))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(e -> assertThat(((ForbiddenException) e).code()).isEqualTo("REP-3001"));
    }

    @Test
    void openRejectsAcceptedQuestionWithRep1008() {
        qa.given(10, 5, 3L, "跳表和红黑树在工程上如何取舍？", 50L, false);
        identity.givenCourseManager(9);
        accounts.given(9, 0, 1000, 0);

        assertThatThrownBy(() -> service.open(teacher(), 10, 50, 7))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo("REP-1008"));
    }

    @Test
    void openRejectsWhenAvailablePointsAreNotEnough() {
        givenOpenQuestion();
        accounts.given(9, 0, 10, 0);

        assertThatThrownBy(() -> service.open(teacher(), 10, 50, 7))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo("REP-1002"));
    }

    @Test
    void settleOnAcceptAwardsWinnerAndTransfersFrozenPoints() {
        givenOpenQuestion();
        long bountyId = service.open(teacher(), 10, 50, 7);

        service.settleOnAccept(10, 50, 7, "accept-1");

        Bounty bounty = bounties.findLatestByQuestion(10).orElseThrow();
        assertThat(bounty.status()).isEqualTo(BountyStatus.AWARDED);
        assertThat(bounty.winnerId()).isEqualTo(7L);
        assertThat(accounts.get(9).frozenPoints()).isZero();
        assertThat(accounts.get(7).reputation()).isEqualTo(50);
        assertThat(ledger.entries()).hasSize(1);
        assertThat(ledger.entries().get(0).reason()).isEqualTo(ReputationReason.BOUNTY_AWARDED);
        assertThat(ledger.entries().get(0).refId()).isEqualTo(bountyId);
        assertThat(events.of(BountyAwardedEvent.class).get(0).winnerId()).isEqualTo(7);
        assertThat(audit.records()).contains("BOUNTY_AWARDED|7|QUESTION|10");
    }

    @Test
    void settleOnAcceptIsNoOpWhenThereIsNoOpenBounty() {
        service.settleOnAccept(10, 50, 7, "accept-1");

        assertThat(ledger.entries()).isEmpty();
        assertThat(events.published()).isEmpty();
    }

    @Test
    void sponsorWinningOwnBountyClosesAndRefundsInsteadOfAwarding() {
        givenOpenQuestion();
        service.open(teacher(), 10, 50, 7);

        service.settleOnAccept(10, 50, 9, "accept-2");

        Bounty bounty = bounties.findLatestByQuestion(10).orElseThrow();
        assertThat(bounty.status()).isEqualTo(BountyStatus.EXPIRED);
        assertThat(accounts.get(9).availablePoints()).isEqualTo(1000);
        assertThat(accounts.get(9).frozenPoints()).isZero();
        assertThat(events.of(BountyClosedEvent.class).get(0).refundedPoints()).isEqualTo(50);
        assertThat(ledger.entries()).isEmpty();
    }

    @Test
    void expireDueClosesOverdueBountyAndRefunds() {
        givenOpenQuestion();
        service.open(teacher(), 10, 50, 1);

        int closed = serviceAt(NOW.plus(Duration.ofDays(2))).expireDue();

        assertThat(closed).isEqualTo(1);
        assertThat(bounties.findLatestByQuestion(10).orElseThrow().status()).isEqualTo(BountyStatus.EXPIRED);
        assertThat(accounts.get(9).availablePoints()).isEqualTo(1000);
        assertThat(audit.records()).contains("BOUNTY_CLOSED|9|QUESTION|10");
    }

    @Test
    void expireDueReturnsZeroWhenNothingIsDue() {
        givenOpenQuestion();
        service.open(teacher(), 10, 50, 7);

        assertThat(serviceAt(NOW.plus(Duration.ofDays(1))).expireDue()).isZero();
        assertThat(bounties.findLatestByQuestion(10).orElseThrow().status()).isEqualTo(BountyStatus.OPEN);
    }

    @Test
    void latestReturnsViewWithSponsorNameAndIsEmptyWhenNeverOpened() {
        givenOpenQuestion();
        identity.given(9, "王老师", true);
        service.open(teacher(), 10, 50, 7);

        BountyView view = service.latest(10).orElseThrow();
        assertThat(view.questionId()).isEqualTo(10);
        assertThat(view.sponsorName()).isEqualTo("王老师");
        assertThat(view.points()).isEqualTo(50);
        assertThat(view.status()).isEqualTo("OPEN");
        assertThat(view.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));

        assertThat(service.latest(404)).isEmpty();
    }

    @Test
    void latestFallsBackWhenSponsorHasNoIdentityRecord() {
        givenOpenQuestion();
        service.open(teacher(), 10, 50, 7);

        assertThat(service.latest(10).orElseThrow().sponsorName()).isEqualTo("未知用户");
    }
}
