package com.campusoverflow.reputation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.reputation.application.fake.ReputationTestFixtures;
import com.campusoverflow.reputation.domain.BadgeType;
import com.campusoverflow.reputation.domain.LedgerEntry;
import com.campusoverflow.reputation.domain.ReputationReason;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReputationQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    private final ReputationTestFixtures.Accounts accounts = new ReputationTestFixtures.Accounts();
    private final ReputationTestFixtures.Ledger ledger = new ReputationTestFixtures.Ledger();
    private final ReputationTestFixtures.Badges badges = new ReputationTestFixtures.Badges();
    private final ReputationTestFixtures.Identity identity = new ReputationTestFixtures.Identity();
    private final ReputationQueryService service = new ReputationQueryService(accounts, ledger, badges, identity);

    private static Actor student(long id) {
        return new Actor(id, "用户" + id, Role.STUDENT);
    }

    private static Actor admin(long id) {
        return new Actor(id, "管理员" + id, Role.ADMIN);
    }

    @Test
    void profileExposesReputationAndBadgesButPointsOnlyToSelf() {
        accounts.given(7, 15, 1000, 50);
        identity.given(7, "小红", true);
        badges.award(new com.campusoverflow.reputation.domain.BadgeAward(7, BadgeType.FIRST_ACCEPTED, NOW));

        ReputationProfileView self = service.profile(7, student(7));
        assertThat(self.displayName()).isEqualTo("小红");
        assertThat(self.reputation()).isEqualTo(15);
        assertThat(self.availablePoints()).isEqualTo(1000);
        assertThat(self.frozenPoints()).isEqualTo(50);
        assertThat(self.badges()).hasSize(1);
        assertThat(self.badges().get(0).code()).isEqualTo("FIRST_ACCEPTED");
        assertThat(self.badges().get(0).name()).isEqualTo(BadgeType.FIRST_ACCEPTED.displayName());

        ReputationProfileView other = service.profile(7, student(8));
        assertThat(other.reputation()).isEqualTo(15);
        assertThat(other.availablePoints()).isNull();
        assertThat(other.frozenPoints()).isNull();

        ReputationProfileView anonymous = service.profile(7, null);
        assertThat(anonymous.availablePoints()).isNull();
    }

    @Test
    void profileOfUserWithoutAccountOrIdentityFallsBackGracefully() {
        ReputationProfileView view = service.profile(99, student(99));

        assertThat(view.reputation()).isZero();
        assertThat(view.displayName()).isEqualTo("未知用户");
        assertThat(view.badges()).isEmpty();
    }

    @Test
    void ledgerIsReadableByOwnerAndAdminOnly() {
        ledger.append(LedgerEntry.of(7, "e1", ReputationReason.ANSWER_ACCEPTED, 15, "ANSWER", 50, NOW));
        PageRequest page = new PageRequest(1, 20);

        PageResult<LedgerEntryView> mine = service.ledger(7, student(7), page);
        assertThat(mine.items()).hasSize(1);
        assertThat(mine.items().get(0).reason()).isEqualTo("ANSWER_ACCEPTED");
        assertThat(mine.items().get(0).reasonLabel()).isEqualTo(ReputationReason.ANSWER_ACCEPTED.label());
        assertThat(mine.items().get(0).delta()).isEqualTo(15);
        assertThat(mine.items().get(0).id()).isPositive();

        assertThat(service.ledger(7, admin(1), page).items()).hasSize(1);

        assertThatThrownBy(() -> service.ledger(7, student(8), page))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(e -> assertThat(((ForbiddenException) e).code()).isEqualTo("REP-3002"));

        assertThatThrownBy(() -> service.ledger(7, null, page))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void ledgerOfUnknownUserIsEmptyRatherThanError() {
        assertThat(service.ledger(404, student(404), new PageRequest(1, 20)).items()).isEmpty();
    }
}
