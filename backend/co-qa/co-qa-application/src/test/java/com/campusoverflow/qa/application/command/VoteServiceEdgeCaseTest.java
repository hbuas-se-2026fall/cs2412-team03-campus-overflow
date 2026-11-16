package com.campusoverflow.qa.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.qa.api.event.VoteCastEvent;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryAnswers;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryQuestions;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryVotes;
import com.campusoverflow.qa.application.fake.QaTestFixtures.RecordingEvents;
import com.campusoverflow.qa.domain.MarkdownBody;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.Tags;
import com.campusoverflow.qa.domain.TargetType;
import com.campusoverflow.qa.domain.Title;
import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 投票的边界：撤销、重复投同一值、非法值、给已删除内容投票、目标不存在。
 * 原有测试只覆盖「改票增量」与「不能自投」，其余分支一条都没测。
 */
class VoteServiceEdgeCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryQuestions questions = new InMemoryQuestions();
    private final InMemoryAnswers answers = new InMemoryAnswers();
    private final InMemoryVotes votes = new InMemoryVotes();
    private final RecordingEvents events = new RecordingEvents();

    private final Actor ming = new Actor(1, "小明", Role.STUDENT);
    private final Actor hong = new Actor(2, "小红", Role.STUDENT);

    private VoteService service;
    private AnswerCommandService answerService;
    private long questionId;
    private long answerId;

    @BeforeEach
    void setUp() {
        service = new VoteService(votes, questions, answers, events, clock);
        answerService = new AnswerCommandService(questions, answers,
                new com.campusoverflow.qa.application.fake.QaTestFixtures.StubIdentity()
                        .withUser(1, "小明", Role.STUDENT).withUser(2, "小红", Role.STUDENT),
                events, new com.campusoverflow.qa.application.fake.QaTestFixtures.CountingCache(),
                new com.campusoverflow.qa.application.fake.QaTestFixtures.RecordingAudit(), clock);
        Question q = questions.save(Question.post(ming.userId(), 100L, new Title("红黑树插入后为什么必须旋转？"),
                MarkdownBody.ofQuestion("变色为什么不够？希望有直观解释，最好能给出一个反例。"),
                Tags.of(List.of("数据结构", "红黑树")), NOW));
        questionId = q.id();
        answerId = answerService.submit(hong, questionId, "关键在于黑高这个不变量，仅靠变色无法调整。");
    }

    @Test
    void 非法投票值被拒绝() {
        assertThatThrownBy(() -> service.cast(hong, TargetType.QUESTION, questionId, 2))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("QA-1016");
        assertThatThrownBy(() -> service.cast(hong, TargetType.QUESTION, questionId, -3))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void 重复投同一个值不会重复改变分数() {
        assertThat(service.cast(hong, TargetType.QUESTION, questionId, 1).scoreDelta()).isEqualTo(1);
        assertThat(service.cast(hong, TargetType.QUESTION, questionId, 1).scoreDelta()).isZero();

        assertThat(questions.scoreDeltas).containsEntry(questionId, 1);
    }

    @Test
    void 撤销投票把分数改回去() {
        service.cast(hong, TargetType.QUESTION, questionId, 1);

        VoteService.VoteResult revoked = service.cast(hong, TargetType.QUESTION, questionId, 0);

        assertThat(revoked.myVote()).isZero();
        assertThat(revoked.scoreDelta()).isEqualTo(-1);
        assertThat(questions.scoreDeltas).containsEntry(questionId, 0);
    }

    @Test
    void 撤销一个不存在的投票是无操作() {
        VoteService.VoteResult result = service.cast(hong, TargetType.QUESTION, questionId, 0);

        assertThat(result.myVote()).isZero();
        assertThat(result.scoreDelta()).isZero();
        assertThat(questions.scoreDeltas).isEmpty();
        assertThat(events.ofType(VoteCastEvent.class)).isEmpty();
    }

    @Test
    void 给回答投票走回答的分数通道() {
        VoteService.VoteResult result = service.cast(ming, TargetType.ANSWER, answerId, 1);

        assertThat(result.scoreDelta()).isEqualTo(1);
        assertThat(answers.scoreDeltas).containsEntry(answerId, 1);
        assertThat(questions.scoreDeltas).isEmpty();
    }

    @Test
    void 投票事件携带新旧值与所属问题供声誉幂等结算() {
        service.cast(ming, TargetType.ANSWER, answerId, 1);
        service.cast(ming, TargetType.ANSWER, answerId, -1);

        List<VoteCastEvent> published = events.ofType(VoteCastEvent.class);
        assertThat(published).hasSize(2);
        assertThat(published.get(0).oldValue()).isZero();
        assertThat(published.get(0).newValue()).isEqualTo(1);
        assertThat(published.get(1).oldValue()).isEqualTo(1);
        assertThat(published.get(1).newValue()).isEqualTo(-1);
        assertThat(published.get(1).questionId()).isEqualTo(questionId);
        assertThat(published.get(1).targetAuthorId()).isEqualTo(hong.userId());
    }

    @Test
    void 目标不存在时分别返回QA4001与QA4002() {
        assertThatThrownBy(() -> service.cast(hong, TargetType.QUESTION, 404L, 1))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("QA-4001");
        assertThatThrownBy(() -> service.cast(hong, TargetType.ANSWER, 404L, 1))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("QA-4002");
    }

    @Test
    void 已删除的内容不能再投票() {
        answerService.delete(hong, answerId);
        Question question = questions.findById(questionId).orElseThrow();
        question.softDelete(ming, false, NOW);
        questions.save(question);

        assertThatThrownBy(() -> service.cast(hong, TargetType.ANSWER, answerId, 1))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("QA-4002");
        assertThatThrownBy(() -> service.cast(hong, TargetType.QUESTION, questionId, 1))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("QA-4001");
    }

    @Test
    void 不能给已删除的回答投票即使它属于同一个问题() {
        answerService.delete(hong, answerId);

        assertThatThrownBy(() -> service.cast(ming, TargetType.ANSWER, answerId, 1))
                .isInstanceOf(NotFoundException.class);
    }
}
