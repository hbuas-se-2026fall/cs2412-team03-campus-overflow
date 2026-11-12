package com.campusoverflow.qa.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.qa.api.QuestionRef;
import com.campusoverflow.qa.application.fake.QaTestFixtures;
import com.campusoverflow.qa.domain.MarkdownBody;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.Tags;
import com.campusoverflow.qa.domain.Title;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** QaQueryApiImpl 是其他上下文读问题的唯一入口，映射错一个字段下游就会误判（比如把已删除问题当真）。 */
class QaQueryApiImplTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    private static Question savedQuestion(QaTestFixtures.InMemoryQuestions repo, String rawTitle) {
        Question question = Question.post(7L, 3L, new Title(rawTitle),
                MarkdownBody.ofQuestion("这是一段足够长的正文，用于通过实体构造的业务规则校验。"),
                Tags.of(List.of("数据结构")), NOW);
        return repo.save(question);
    }

    @Test
    void mapsAllFieldsNeededByOtherContexts() {
        var repo = new QaTestFixtures.InMemoryQuestions();
        Question question = savedQuestion(repo, "跳表和红黑树在工程上如何取舍？");

        Optional<QuestionRef> ref = new QaQueryApiImpl(repo).findQuestion(question.id());

        assertThat(ref).isPresent();
        assertThat(ref.get().id()).isEqualTo(question.id());
        assertThat(ref.get().authorId()).isEqualTo(7L);
        assertThat(ref.get().courseId()).isEqualTo(3L);
        assertThat(ref.get().title()).isEqualTo("跳表和红黑树在工程上如何取舍？");
        assertThat(ref.get().acceptedAnswerId()).isNull();
        assertThat(ref.get().deleted()).isFalse();
    }

    @Test
    void returnsEmptyForUnknownQuestion() {
        var repo = new QaTestFixtures.InMemoryQuestions();

        assertThat(new QaQueryApiImpl(repo).findQuestion(999L)).isEmpty();
    }

    @Test
    void returnsEmptyForNonPositiveIdWithoutTouchingRepository() {
        var repo = new QaTestFixtures.InMemoryQuestions();

        assertThat(new QaQueryApiImpl(repo).findQuestion(0L)).isEmpty();
        assertThat(new QaQueryApiImpl(repo).findQuestion(-3L)).isEmpty();
    }
}
