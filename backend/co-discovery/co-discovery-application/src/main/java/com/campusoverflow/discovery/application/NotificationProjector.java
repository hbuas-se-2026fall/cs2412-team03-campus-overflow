package com.campusoverflow.discovery.application;

import com.campusoverflow.discovery.domain.notification.MentionParser;
import com.campusoverflow.discovery.domain.notification.Notification;
import com.campusoverflow.discovery.domain.notification.NotificationRepository;
import com.campusoverflow.discovery.domain.notification.NotificationType;
import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.qa.api.QaQueryApi;
import com.campusoverflow.qa.api.QuestionRef;
import com.campusoverflow.qa.api.event.AnswerAcceptedEvent;
import com.campusoverflow.qa.api.event.AnswerEndorsedEvent;
import com.campusoverflow.qa.api.event.AnswerSubmittedEvent;
import com.campusoverflow.qa.api.event.CommentPostedEvent;
import com.campusoverflow.reputation.api.event.BadgeAwardedEvent;
import com.campusoverflow.reputation.api.event.BountyAwardedEvent;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 通知中心：把上游事件转换为站内通知。幂等键 (sourceEventId, recipientId)。 */
@Component
public class NotificationProjector {

    /** 用户已注销或跨上下文查询不到时的兜底显示名，不阻断通知投递。 */
    private static final String UNKNOWN_USER = "某同学";

    private final NotificationRepository notifications;
    private final IdentityApi identity;
    private final QaQueryApi qa;

    public NotificationProjector(NotificationRepository notifications, IdentityApi identity, QaQueryApi qa) {
        this.notifications = notifications;
        this.identity = identity;
        this.qa = qa;
    }

    @EventListener
    @Transactional
    public void on(AnswerSubmittedEvent e) {
        if (e.questionAuthorId() == e.answerAuthorId()) {
            return; // 自问自答不打扰自己
        }
        notify(e.questionAuthorId(), NotificationType.ANSWER_RECEIVED,
                name(e.answerAuthorId()) + " 回答了你的问题《" + e.questionTitle() + "》",
                "/questions/" + e.questionId(), e.eventId(), e.occurredAt());
    }

    @EventListener
    @Transactional
    public void on(AnswerAcceptedEvent e) {
        notify(e.answerAuthorId(), NotificationType.ANSWER_ACCEPTED,
                "你的回答被采纳：《" + e.questionTitle() + "》",
                "/questions/" + e.questionId(), e.eventId(), e.occurredAt());
    }

    @EventListener
    @Transactional
    public void on(AnswerEndorsedEvent e) {
        notify(e.answerAuthorId(), NotificationType.ANSWER_ENDORSED,
                "你的回答获得教师认证：《" + e.questionTitle() + "》",
                "/questions/" + e.questionId(), e.eventId(), e.occurredAt());
    }

    @EventListener
    @Transactional
    public void on(CommentPostedEvent e) {
        String questionTitle = qa.findQuestion(e.questionId()).map(QuestionRef::title).orElse("该问题");
        for (String mention : MentionParser.extract(e.body())) {
            // displayName 是 Identity 的公开唯一标识，查不到就不猜测用户
            identity.findByDisplayName(mention)
                    .map(UserSummary::id)
                    .filter(id -> id != e.authorId()) // 不提醒自己
                    .ifPresent(id -> notify(id, NotificationType.MENTIONED,
                            name(e.authorId()) + " 在《" + questionTitle + "》的评论中提到了你",
                            "/questions/" + e.questionId(), e.eventId(), e.occurredAt()));
        }
    }

    @EventListener
    @Transactional
    public void on(BountyAwardedEvent e) {
        notify(e.winnerId(), NotificationType.BOUNTY_AWARDED,
                "你获得了悬赏奖励：" + e.points() + " 积分",
                "/questions/" + e.questionId(), e.eventId(), e.occurredAt());
    }

    @EventListener
    @Transactional
    public void on(BadgeAwardedEvent e) {
        notify(e.userId(), NotificationType.BADGE_AWARDED,
                "恭喜获得徽章「" + e.badgeName() + "」",
                "/users/" + e.userId(), e.eventId(), e.occurredAt());
    }

    private void notify(long recipientId, NotificationType type, String title, String link, String eventId,
                        Instant at) {
        if (recipientId <= 0) {
            return;
        }
        if (notifications.exists(eventId, recipientId)) {
            return; // 同一事件对同一收件人只投递一次
        }
        notifications.save(Notification.create(recipientId, type, title, link, eventId, at));
    }

    private String name(long userId) {
        return identity.findUser(userId).map(UserSummary::displayName).orElse(UNKNOWN_USER);
    }
}
