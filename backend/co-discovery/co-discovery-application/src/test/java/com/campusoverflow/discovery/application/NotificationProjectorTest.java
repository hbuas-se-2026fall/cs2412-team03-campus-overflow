package com.campusoverflow.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.discovery.domain.notification.Notification;
import com.campusoverflow.discovery.domain.notification.NotificationType;
import com.campusoverflow.qa.api.QaQueryApi;
import com.campusoverflow.qa.api.QuestionRef;
import com.campusoverflow.qa.api.event.AnswerAcceptedEvent;
import com.campusoverflow.qa.api.event.AnswerEndorsedEvent;
import com.campusoverflow.qa.api.event.AnswerSubmittedEvent;
import com.campusoverflow.qa.api.event.CommentPostedEvent;
import com.campusoverflow.reputation.api.event.BadgeAwardedEvent;
import com.campusoverflow.reputation.api.event.BountyAwardedEvent;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NotificationProjectorTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private static final QaQueryApi QA = id -> Optional.of(new QuestionRef(id, 11L, 7L, "跳表与红黑树", null, false));

    private static NotificationProjector projector(DiscoveryFakes.Notifications repo,
                                                   DiscoveryFakes.Identities identities) {
        return new NotificationProjector(repo, identities, QA);
    }

    @Test
    void acceptedAnswerNotifiesTheAnswerAuthor() {
        var repo = new DiscoveryFakes.Notifications();
        var identities = new DiscoveryFakes.Identities().add(11, "小明").add(12, "小红");

        projector(repo, identities).on(new AnswerAcceptedEvent("e1", NOW, 2, 1, 12, 11, 11, "跳表与红黑树"));

        assertThat(repo.saved).hasSize(1);
        Notification n = repo.saved.get(0);
        assertThat(n.recipientId()).isEqualTo(12);
        assertThat(n.type()).isEqualTo(NotificationType.ANSWER_ACCEPTED);
        assertThat(n.title()).contains("被采纳");
        assertThat(n.link()).isEqualTo("/questions/1");
        assertThat(n.sourceEventId()).isEqualTo("e1");
    }

    @Test
    void answerSubmittedNotifiesQuestionAuthorButSkipsSelfAnswers() {
        var repo = new DiscoveryFakes.Notifications();
        var identities = new DiscoveryFakes.Identities().add(11, "小明").add(12, "小红");
        var projector = projector(repo, identities);

        projector.on(new AnswerSubmittedEvent("e1", NOW, 2, 1, 12, 11, "跳表与红黑树"));
        projector.on(new AnswerSubmittedEvent("e2", NOW, 3, 1, 11, 11, "跳表与红黑树"));

        assertThat(repo.saved).hasSize(1);
        assertThat(repo.saved.get(0).recipientId()).isEqualTo(11);
        assertThat(repo.saved.get(0).type()).isEqualTo(NotificationType.ANSWER_RECEIVED);
        assertThat(repo.saved.get(0).title()).contains("小红");
    }

    @Test
    void mentionsNotifyOnlyKnownUsersAndAreDeduplicated() {
        var repo = new DiscoveryFakes.Notifications();
        var identities = new DiscoveryFakes.Identities().add(7, "小明").add(12, "小红");

        projector(repo, identities).on(new CommentPostedEvent("e1", NOW, 5, 1, 12,
                "谢谢 @小明，也请 @小明 再看下 @查无此人"));

        assertThat(repo.saved).hasSize(1);
        assertThat(repo.saved.get(0).recipientId()).isEqualTo(7);
        assertThat(repo.saved.get(0).type()).isEqualTo(NotificationType.MENTIONED);
        assertThat(repo.saved.get(0).title()).contains("跳表与红黑树");
    }

    @Test
    void mentionOfTheCommentAuthorDoesNotNotifyHimself() {
        var repo = new DiscoveryFakes.Notifications();
        var identities = new DiscoveryFakes.Identities().add(12, "小红");

        projector(repo, identities).on(new CommentPostedEvent("e1", NOW, 5, 1, 12, "@小红 自我提醒"));

        assertThat(repo.saved).isEmpty();
    }

    @Test
    void replayedEventDoesNotCreateDuplicateNotification() {
        var repo = new DiscoveryFakes.Notifications();
        var projector = projector(repo, new DiscoveryFakes.Identities().add(12, "小红"));
        var event = new AnswerAcceptedEvent("same", NOW, 2, 1, 12, 11, 11, "跳表与红黑树");

        projector.on(event);
        projector.on(event);

        assertThat(repo.saved).hasSize(1);
    }

    @Test
    void sameEventMayNotifyDifferentRecipients() {
        var repo = new DiscoveryFakes.Notifications();
        var identities = new DiscoveryFakes.Identities().add(7, "小明").add(8, "小刚").add(12, "小红");

        projector(repo, identities).on(new CommentPostedEvent("e1", NOW, 5, 1, 12, "@小明 @小刚"));

        assertThat(repo.saved).hasSize(2);
        assertThat(repo.saved).extracting(Notification::recipientId).containsExactlyInAnyOrder(7L, 8L);
    }

    @Test
    void endorsementBountyAndBadgeNotifications() {
        var repo = new DiscoveryFakes.Notifications();
        var projector = projector(repo, new DiscoveryFakes.Identities().add(12, "小红"));

        projector.on(new AnswerEndorsedEvent("e1", NOW, 2, 1, 12, 99, "跳表与红黑树"));
        projector.on(new BountyAwardedEvent("e2", NOW, 3, 1, 11, 12, 50));
        projector.on(new BadgeAwardedEvent("e3", NOW, 12, "FIRST_ACCEPTED", "首次被采纳"));

        assertThat(repo.saved).extracting(Notification::type).containsExactly(
                NotificationType.ANSWER_ENDORSED, NotificationType.BOUNTY_AWARDED, NotificationType.BADGE_AWARDED);
        assertThat(repo.saved.get(1).title()).contains("50");
        assertThat(repo.saved.get(2).title()).contains("首次被采纳");
    }

    @Test
    void unknownUserFallsBackToPlaceholderDisplayName() {
        var repo = new DiscoveryFakes.Notifications();
        var identities = new DiscoveryFakes.Identities().add(11, "小明");

        projector(repo, identities).on(new AnswerSubmittedEvent("e1", NOW, 2, 1, 12, 11, "跳表与红黑树"));

        assertThat(repo.saved.get(0).title()).contains("某同学");
    }
}
