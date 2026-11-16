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

/**
 * 索引投影器的**幂等性**：每个事件处理器都有 `if (!firstTime(e)) return;`，
 * 但原有测试只验证了其中一类事件的重复投递。这里把 10 个处理器逐个重放一遍，
 * 确认「同一条事件重复消费只产生一次索引操作」——这是 Outbox 模式重投时的正确性底线。
 */
class SearchIndexProjectorIdempotencyTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

    private final DiscoveryFakes.SearchIndexes index = new DiscoveryFakes.SearchIndexes();
    private final DiscoveryFakes.Processed processed = new DiscoveryFakes.Processed();
    private final SearchIndexProjector projector = new SearchIndexProjector(index, processed);

    @Test
    void 问题发布事件重放只插入一次() {
        QuestionPostedEvent event = new QuestionPostedEvent("q-post", NOW, 1, 11, 7L, "标题", "正文", List.of("a"));

        projector.on(event);
        projector.on(event);
        projector.on(event);

        assertThat(index.calls).containsExactly("insert:1:1");
    }

    @Test
    void 问题编辑事件重放只更新一次() {
        QuestionEditedEvent event = new QuestionEditedEvent("q-edit", NOW, 1, "新标题", "新正文", List.of("c"));

        projector.on(event);
        projector.on(event);

        assertThat(index.calls).containsExactly("updateText:1:1");
    }

    @Test
    void 回答提交事件重放只加一次回答数() {
        AnswerSubmittedEvent event = new AnswerSubmittedEvent("a-submit", NOW, 2, 1, 12, 11, "标题");

        projector.on(event);
        projector.on(event);

        assertThat(index.calls).containsExactly("answerCount:1:1");
    }

    @Test
    void 删除问题事件重放只标记一次删除() {
        ContentDeletedEvent event = new ContentDeletedEvent("d-question", NOW, "QUESTION", 1, 1);

        projector.on(event);
        projector.on(event);

        assertThat(index.calls).containsExactly("deleted:1");
    }

    @Test
    void 删除回答事件重放只减一次回答数() {
        ContentDeletedEvent event = new ContentDeletedEvent("d-answer", NOW, "ANSWER", 2, 1);

        projector.on(event);
        projector.on(event);

        assertThat(index.calls).containsExactly("answerCount:1:-1");
    }

    @Test
    void 采纳事件重放只标记一次已采纳() {
        AnswerAcceptedEvent event = new AnswerAcceptedEvent("a-accept", NOW, 2, 1, 12, 11, 11, "标题");

        projector.on(event);
        projector.on(event);

        assertThat(index.calls).containsExactly("accepted:1");
    }

    @Test
    void 投票事件重放只调一次分数() {
        VoteCastEvent event = new VoteCastEvent("v-cast", NOW, "QUESTION", 1, 11, 12, 1, 1, -1);

        projector.on(event);
        projector.on(event);

        assertThat(index.calls).containsExactly("score:1:-2");
    }

    @Test
    void 悬赏三个事件各自重放只生效一次() {
        BountyOpenedEvent opened = new BountyOpenedEvent("b-open", NOW, 3, 1, 11, 50, NOW.plusSeconds(3600));
        BountyAwardedEvent awarded = new BountyAwardedEvent("b-award", NOW, 3, 1, 11, 12, 50);
        BountyClosedEvent closed = new BountyClosedEvent("b-close", NOW, 4, 1, 11, 50);

        projector.on(opened);
        projector.on(opened);
        projector.on(awarded);
        projector.on(awarded);
        projector.on(closed);
        projector.on(closed);

        assertThat(index.calls).containsExactly("bounty:1:true", "bounty:1:false", "bounty:1:false");
    }

    @Test
    void 不同事件id即使内容相同也会各执行一次() {
        projector.on(new AnswerSubmittedEvent("e-1", NOW, 2, 1, 12, 11, "标题"));
        projector.on(new AnswerSubmittedEvent("e-2", NOW, 3, 1, 13, 11, "标题"));

        assertThat(index.calls).containsExactly("answerCount:1:1", "answerCount:1:1");
    }

    @Test
    void 目标类型大小写不影响判定() {
        projector.on(new ContentDeletedEvent("c-1", NOW, "question", 1, 1));
        projector.on(new VoteCastEvent("c-2", NOW, "Question", 1, 11, 12, 1, 0, 1));

        assertThat(index.calls).containsExactly("deleted:1", "score:1:1");
    }
}
