package com.campusoverflow.reputation.application;

import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.qa.api.QaQueryApi;
import com.campusoverflow.qa.api.QuestionRef;
import com.campusoverflow.reputation.api.event.BountyAwardedEvent;
import com.campusoverflow.reputation.api.event.BountyClosedEvent;
import com.campusoverflow.reputation.api.event.BountyOpenedEvent;
import com.campusoverflow.reputation.domain.Bounty;
import com.campusoverflow.reputation.domain.BountyRepository;
import com.campusoverflow.reputation.domain.ReputationAccount;
import com.campusoverflow.reputation.domain.ReputationAccountRepository;
import com.campusoverflow.reputation.domain.ReputationReason;
import com.campusoverflow.reputation.domain.ReputationRulebook;
import com.campusoverflow.shared.audit.AuditTrail;
import com.campusoverflow.shared.domain.ConflictException;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.event.EventIds;
import com.campusoverflow.shared.event.IntegrationEventPublisher;
import com.campusoverflow.shared.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/** 用例：悬赏的发起、结算、到期退款。积分冻结与悬赏状态在同一事务中变更。 */
@Service
public class BountyService {

    private static final int EXPIRE_BATCH = 100;

    private final BountyRepository bounties;
    private final ReputationAccountRepository accounts;
    private final ReputationLedgerService ledgerService;
    private final QaQueryApi qa;
    private final IdentityApi identity;
    private final IntegrationEventPublisher events;
    private final AuditTrail audit;
    private final Clock clock;

    public BountyService(BountyRepository bounties, ReputationAccountRepository accounts,
                         ReputationLedgerService ledgerService, QaQueryApi qa, IdentityApi identity,
                         IntegrationEventPublisher events, AuditTrail audit, Clock clock) {
        this.bounties = bounties;
        this.accounts = accounts;
        this.ledgerService = ledgerService;
        this.qa = qa;
        this.identity = identity;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * 发起悬赏：校验「课程管理者 + 问题仍开放 + 积分/期限在范围内」，然后把积分从可用转为冻结。
     * 同一问题同时只允许一个进行中的悬赏。
     */
    @Transactional
    public long open(Actor actor, long questionId, int points, int days) {
        QuestionRef question = qa.findQuestion(questionId)
                .orElseThrow(() -> new NotFoundException("REP-4001", "问题不存在或已被删除"));
        if (bounties.findOpenByQuestion(questionId).isPresent()) {
            throw new ConflictException("REP-2002", "该问题已有进行中的悬赏，请等它结束");
        }

        boolean questionOpen = !question.deleted() && question.acceptedAnswerId() == null;
        boolean managesCourse = identity.canManageCourse(actor.userId(), question.courseId());
        Instant now = clock.instant();

        Bounty bounty = Bounty.open(questionId, questionOpen, actor, managesCourse, points, days, now);
        // 账户可能还没被 UserRegisteredEvent 开出来（事件是异步的），按角色兜底发放初始积分，
        // 否则刚注册的教师立刻发悬赏会因“可用积分为 0”被拒。
        ReputationAccount sponsor = accounts.findByUserId(actor.userId())
                .orElseGet(() -> ReputationAccount.open(actor.userId(), ReputationRulebook.initialPoints(actor.role())));
        sponsor.freezePoints(points);
        accounts.save(sponsor);

        Bounty saved = bounties.save(bounty);
        long bountyId = idOf(saved);
        events.publish(new BountyOpenedEvent(EventIds.next(), now, bountyId, questionId, actor.userId(), points,
                saved.expiresAt()));
        audit.record("BOUNTY_OPENED", actor.userId(), "QUESTION", String.valueOf(questionId),
                "悬赏 " + points + " 积分，期限 " + days + " 天");
        return bountyId;
    }

    /**
     * 由 AnswerAccepted 事件触发（在处理器事务内）。幂等：没有进行中的悬赏就直接返回。
     * 获奖者恰好是发起人本人时不给分，按规则关闭并退回冻结积分。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleOnAccept(long questionId, long answerId, long winnerId, String sourceEventId) {
        Optional<Bounty> maybe = bounties.findOpenByQuestion(questionId);
        if (maybe.isEmpty()) {
            return;
        }
        Bounty bounty = maybe.get();
        Instant now = clock.instant();
        ReputationAccount sponsor = accounts.findByUserId(bounty.sponsorId())
                .orElseGet(() -> ReputationAccount.open(bounty.sponsorId(), 0));

        if (winnerId == bounty.sponsorId()) {
            closeAndRefund(bounty, sponsor, now);
            return;
        }

        bounty.award(answerId, winnerId, now);
        Bounty saved = bounties.save(bounty);
        sponsor.spendFrozen(bounty.points());
        accounts.save(sponsor);

        long bountyId = idOf(saved);
        // 悬赏积分按“声誉形式”记到获奖者账本（见 ReputationAccount 的注释）
        ledgerService.record(winnerId, sourceEventId, ReputationReason.BOUNTY_AWARDED, bounty.points(),
                "BOUNTY", bountyId);
        events.publish(new BountyAwardedEvent(EventIds.next(), now, bountyId, questionId, bounty.sponsorId(),
                winnerId, bounty.points()));
        audit.record("BOUNTY_AWARDED", winnerId, "QUESTION", String.valueOf(questionId),
                "获得悬赏 " + bounty.points() + " 积分");
    }

    /** 到期未采纳的悬赏：关闭并退回冻结积分。由定时任务调用。 */
    @Transactional
    public int expireDue() {
        Instant now = clock.instant();
        List<Bounty> due = bounties.findDue(now, EXPIRE_BATCH);
        int closed = 0;
        for (Bounty bounty : due) {
            Optional<ReputationAccount> sponsor = accounts.findByUserId(bounty.sponsorId());
            if (sponsor.isEmpty()) {
                continue;
            }
            closeAndRefund(bounty, sponsor.get(), now);
            closed++;
        }
        return closed;
    }

    @Transactional(readOnly = true)
    public Optional<BountyView> latest(long questionId) {
        return bounties.findLatestByQuestion(questionId)
                .map(b -> new BountyView(idOf(b), b.questionId(), b.sponsorId(), sponsorName(b.sponsorId()),
                        b.points(), b.status().name(), b.createdAt(), b.expiresAt(), b.winnerId(), b.answerId()));
    }

    private void closeAndRefund(Bounty bounty, ReputationAccount sponsor, Instant now) {
        bounty.close(now);
        Bounty saved = bounties.save(bounty);
        sponsor.refundFrozen(bounty.points());
        accounts.save(sponsor);
        events.publish(new BountyClosedEvent(EventIds.next(), now, idOf(saved), bounty.questionId(),
                bounty.sponsorId(), bounty.points()));
        audit.record("BOUNTY_CLOSED", bounty.sponsorId(), "QUESTION", String.valueOf(bounty.questionId()),
                "悬赏关闭并退回 " + bounty.points() + " 积分");
    }

    private String sponsorName(long sponsorId) {
        return identity.findUser(sponsorId).map(UserSummary::displayName).orElse("未知用户");
    }

    private static long idOf(Bounty bounty) {
        Long id = bounty.id();
        return id == null ? 0L : id;
    }
}
