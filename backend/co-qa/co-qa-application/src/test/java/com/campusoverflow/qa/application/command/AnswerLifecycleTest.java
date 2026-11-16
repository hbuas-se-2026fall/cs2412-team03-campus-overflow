package com.campusoverflow.qa.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.qa.api.event.AnswerEndorsedEvent;
import com.campusoverflow.qa.api.event.ContentDeletedEvent;
import com.campusoverflow.qa.application.fake.QaTestFixtures.CountingCache;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryAnswers;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryQuestions;
import com.campusoverflow.qa.application.fake.QaTestFixtures.RecordingAudit;
import com.campusoverflow.qa.application.fake.QaTestFixtures.RecordingEvents;
import com.campusoverflow.qa.application.fake.QaTestFixtures.StubIdentity;
import com.campusoverflow.qa.domain.MarkdownBody;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.Tags;
import com.campusoverflow.qa.domain.Title;
import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.ConflictException;
import com.campusoverflow.shared.domain.ForbiddenException;
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
 * 回答生命周期：编辑 / 删除 / 教师认证 / 采纳的**失败路径**。
 * 原有的 {@code AnswerAndVoteServiceTest} 覆盖了提交与「谁能采纳」，
 * 但错误码、计数器回滚、事件与审计这些分支此前一条都没测——正好是线上最容易出错的地方。
 */
class AnswerLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryQuestions questions = new InMemoryQuestions();
    private final InMemoryAnswers answers = new InMemoryAnswers();
    private final RecordingEvents events = new RecordingEvents();
    private final CountingCache cache = new CountingCache();
    private final RecordingAudit audit = new RecordingAudit();
    private final StubIdentity identity = new StubIdentity()
            .withUser(1, "小明", Role.STUDENT)
            .withUser(2, "小红", Role.STUDENT)
            .withUser(9, "王老师", Role.TEACHER)
            .withCourseManager(9);

    private final Actor ming = new Actor(1, "小明", Role.STUDENT);
    private final Actor hong = new Actor(2, "小红", Role.STUDENT);
    private final Actor teacher = new Actor(9, "王老师", Role.TEACHER);

    private AnswerCommandService service;
    private Question question;
    private long answerId;

    @BeforeEach
    void setUp() {
        service = new AnswerCommandService(questions, answers, identity, events, cache, audit, clock);
        question = questions.save(Question.post(ming.userId(), 100L, new Title("红黑树插入后为什么必须旋转？"),
                MarkdownBody.ofQuestion("变色为什么不够？希望有直观解释，最好能给出一个反例。"),
                Tags.of(List.of("数据结构", "红黑树")), NOW));
        answerId = service.submit(hong, question.id(), "关键在于黑高这个不变量，仅靠变色无法调整。");
    }

    @Test
    void 作者可以编辑自己的回答并刷新缓存() {
        int before = cache.invalidations;

        service.edit(hong, answerId, "补充一句：插入节点默认染红，冲突时才需要旋转。");

        assertThat(answers.findById(answerId).orElseThrow().body().value()).contains("默认染红");
        assertThat(cache.invalidations).isEqualTo(before + 1);
    }

    @Test
    void 非作者且不管课程的人不能编辑() {
        assertThatThrownBy(() -> service.edit(ming, answerId, "我想改别人的回答，这条足够长可以通过校验"))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("QA-3005");
    }

    @Test
    void 课程教师可以编辑他人的回答() {
        service.edit(teacher, answerId, "教师补充：红黑树的五个性质必须同时成立。");

        assertThat(answers.findById(answerId).orElseThrow().body().value()).contains("五个性质");
    }

    @Test
    void 已被采纳的回答不能编辑() {
        service.accept(ming, question.id(), answerId);

        assertThatThrownBy(() -> service.edit(hong, answerId, "采纳后再改一下"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("QA-2006");
    }

    @Test
    void 作者删除自己的回答会把回答数减一并发布删除事件() {
        service.delete(hong, answerId);

        assertThat(answers.findById(answerId).orElseThrow().isDeleted()).isTrue();
        assertThat(questions.answerCountDeltas).containsEntry(question.id(), 0); // +1 提交、-1 删除，净值为 0
        assertThat(events.ofType(ContentDeletedEvent.class)).hasSize(1);
        assertThat(events.ofType(ContentDeletedEvent.class).get(0).targetType()).isEqualTo("ANSWER");
        assertThat(audit.actions).contains("ANSWER_DELETED");
    }

    @Test
    void 被采纳的回答不能删除() {
        service.accept(ming, question.id(), answerId);

        assertThatThrownBy(() -> service.delete(hong, answerId))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("QA-2008");
        assertThat(answers.findById(answerId).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    void 非作者且不管课程的人不能删除() {
        assertThatThrownBy(() -> service.delete(ming, answerId))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("QA-3006");
    }

    @Test
    void 已删除的回答再次操作返回QA4002() {
        service.delete(hong, answerId);

        assertThatThrownBy(() -> service.edit(hong, answerId, "再改一次"))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("QA-4002");
        assertThatThrownBy(() -> service.delete(hong, answerId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 教师认证回答发布事件并可重复检查() {
        service.endorse(teacher, answerId);

        assertThat(answers.findById(answerId).orElseThrow().endorsedBy()).isEqualTo(teacher.userId());
        assertThat(events.ofType(AnswerEndorsedEvent.class)).hasSize(1);
        assertThatThrownBy(() -> service.endorse(teacher, answerId))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("QA-2009");
    }

    @Test
    void 普通学生不能认证回答() {
        assertThatThrownBy(() -> service.endorse(hong, answerId))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("QA-3003");
    }

    @Test
    void 采纳不存在的回答返回QA4002() {
        assertThatThrownBy(() -> service.accept(ming, question.id(), 404L))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("QA-4002");
    }

    @Test
    void 重复采纳返回QA2002() {
        service.accept(ming, question.id(), answerId);

        long another = service.submit(teacher, question.id(), "另一种思路：先左旋再右旋，两次都染色。");
        assertThatThrownBy(() -> service.accept(ming, question.id(), another))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("QA-2002");
    }

    @Test
    void 已删除的问题不能再回答也不能采纳() {
        question.softDelete(ming, false, NOW);
        questions.save(question);

        assertThatThrownBy(() -> service.submit(hong, question.id(), "问题都删了还想回答，这条正文足够长"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("QA-2005");

        assertThatThrownBy(() -> service.accept(ming, question.id(), answerId))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("QA-4001");
    }

    @Test
    void 问题不存在时提交回答返回QA4001() {
        assertThatThrownBy(() -> service.submit(hong, 404L, "问题都找不到"))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("QA-4001");
    }
}
