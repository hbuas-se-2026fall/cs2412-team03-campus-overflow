package com.campusoverflow.reputation.application;

import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.reputation.domain.BadgeAward;
import com.campusoverflow.reputation.domain.BadgeRepository;
import com.campusoverflow.reputation.domain.LedgerEntry;
import com.campusoverflow.reputation.domain.LedgerRepository;
import com.campusoverflow.reputation.domain.ReputationAccount;
import com.campusoverflow.reputation.domain.ReputationAccountRepository;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Actor;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReputationQueryService {

    private static final String UNKNOWN_USER = "未知用户";

    private final ReputationAccountRepository accounts;
    private final LedgerRepository ledger;
    private final BadgeRepository badges;
    private final IdentityApi identity;

    public ReputationQueryService(ReputationAccountRepository accounts, LedgerRepository ledger,
                                  BadgeRepository badges, IdentityApi identity) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.badges = badges;
        this.identity = identity;
    }

    /**
     * 声誉档案：声誉值与徽章公开可见；悬赏积分属于个人数据，只有本人查看时才返回，
     * 他人查看时为 {@code null}（见 {@link ReputationProfileView} 的约定）。
     */
    public ReputationProfileView profile(long userId, Actor viewerOrNull) {
        ReputationAccount account = accounts.findByUserId(userId).orElse(null);
        String displayName = identity.findUser(userId).map(UserSummary::displayName).orElse(UNKNOWN_USER);
        boolean self = viewerOrNull != null && viewerOrNull.is(userId);

        int reputation = account == null ? 0 : account.reputation();
        Integer availablePoints = self && account != null ? account.availablePoints() : null;
        Integer frozenPoints = self && account != null ? account.frozenPoints() : null;

        List<ReputationProfileView.BadgeView> badgeViews = badges.findByUser(userId).stream()
                .map(ReputationQueryService::toBadgeView)
                .toList();
        return new ReputationProfileView(userId, displayName, reputation, availablePoints, frozenPoints, badgeViews);
    }

    /** 声誉流水属于个人数据：仅本人与管理员可查看。 */
    public PageResult<LedgerEntryView> ledger(long userId, Actor viewer, PageRequest page) {
        boolean self = viewer != null && viewer.is(userId);
        if (!self && (viewer == null || !viewer.isAdmin())) {
            throw new ForbiddenException("REP-3002", "只能查看自己的声誉流水");
        }
        return ledger.findByUser(userId, page).map(ReputationQueryService::toView);
    }

    private static ReputationProfileView.BadgeView toBadgeView(BadgeAward award) {
        return new ReputationProfileView.BadgeView(award.type().name(), award.type().displayName(),
                award.type().description(), award.awardedAt());
    }

    private static LedgerEntryView toView(LedgerEntry entry) {
        Long id = entry.id();
        return new LedgerEntryView(id == null ? 0L : id, entry.reason().name(), entry.reason().label(),
                entry.delta(), entry.refType(), entry.refId(), entry.createdAt());
    }
}
