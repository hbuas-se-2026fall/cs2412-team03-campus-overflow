package com.campusoverflow.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.qa.api.event.AnswerAcceptedEvent;
import com.campusoverflow.qa.api.event.AnswerSubmittedEvent;
import com.campusoverflow.qa.api.event.ContentDeletedEvent;
import com.campusoverflow.qa.api.event.QuestionEditedEvent;
import com.campusoverflow.qa.api.event.QuestionPostedEvent;
import com.campusoverflow.qa.api.event.VoteCastEvent;
import com.campusoverflow.reputation.api.event.BountyAwardedEvent;
import com.campusoverflow.reputation.api.event.BountyClosedEvent;
import com.campusoverflow.reputation.api.event.BountyOpenedEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchIndexProjectorTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private static SearchIndexProjector projector(DiscoveryFakes.SearchIndexes index,
                                                  DiscoveryFakes.Processed processed) {
        return new SearchIndexProjector(index, processed);
    }

    @Test
    void everyEventMapsToTheExpectedIndexOperation() {
        var index = new DiscoveryFakes.SearchIndexes();
        var projector = projector(index, new DiscoveryFakes.Processed());

        projector.on(new QuestionPostedEvent("e1", NOW, 1, 11, 7L, "标题", "正文", List.of("a", "b")));
        projector.on(new QuestionEditedEvent("e2", NOW, 1, "新标题", "新正文", List.of("c")));
        projector.on(new AnswerSubmittedEvent("e3", NOW, 2, 1, 12, 11, "标题"));
        projector.on(new ContentDeletedEvent("e4", NOW, "QUESTION", 1, 1));
        projector.on(new ContentDeletedEvent("e5", NOW, "ANSWER", 2, 1));
        projector.on(new AnswerAcceptedEvent("e6", NOW, 2, 1, 12, 11, 11, "标题"));
        projector.on(new VoteCastEvent("e7", NOW, "QUESTION", 1, 11, 12, 1, 0, 1));
        projector.on(new VoteCastEvent("e8", NOW, "ANSWER", 2, 12, 11, 1, 0, 1));
        projector.on(new BountyOpenedEvent("e9", NOW, 3, 1, 11, 50, NOW.plusSeconds(3600)));
        projector.on(new BountyAwardedEvent("e10", NOW, 3, 1, 11, 12, 50));
        projector.on(new BountyClosedEvent("e11", NOW, 4, 1, 11, 50));

        assertThat(index.calls).containsExactly(
                "insert:1:2",
                "updateText:1:1",
                "answerCount:1:1",
                "deleted:1",
                "answerCount:1:-1",
                "accepted:1",
                "score:1:1",   // 只有 QUESTION 票计入问题得分
                "bounty:1:true",
                "bounty:1:false",
                "bounty:1:false");
    }

    @Test
    void voteDeltaIsNewValueMinusOldValue() {
        var index = new DiscoveryFakes.SearchIndexes();
        var projector = projector(index, new DiscoveryFakes.Processed());

        projector.on(new VoteCastEvent("v1", NOW, "QUESTION", 1, 11, 12, 1, 1, -1));

        assertThat(index.calls).containsExactly("score:1:-2");
    }

    @Test
    void replayedEventIsSkippedAndUsesDedicatedConsumerName() {
        var index = new DiscoveryFakes.SearchIndexes();
        var processed = new DiscoveryFakes.Processed();
        var projector = projector(index, processed);
        var event = new QuestionPostedEvent("same", NOW, 1, 11, 7L, "标题", "正文", List.of("a"));

        projector.on(event);
        projector.on(event);

        assertThat(index.calls).containsExactly("insert:1:1");
        assertThat(processed.consumers).containsExactly(SearchIndexProjector.CONSUMER,
                SearchIndexProjector.CONSUMER);
        assertThat(SearchIndexProjector.CONSUMER).isEqualTo("discovery.search-index");
    }

    @Test
    void nullTagListDoesNotBreakDocumentCreation() {
        var index = new DiscoveryFakes.SearchIndexes();
        var projector = projector(index, new DiscoveryFakes.Processed());

        projector.on(new QuestionPostedEvent("n1", NOW, 1, 11, null, "标题", "正文", null));
        projector.on(new QuestionEditedEvent("n2", NOW, 1, "标题", "正文", null));

        assertThat(index.calls).containsExactly("insert:1:0", "updateText:1:0");
    }
}
