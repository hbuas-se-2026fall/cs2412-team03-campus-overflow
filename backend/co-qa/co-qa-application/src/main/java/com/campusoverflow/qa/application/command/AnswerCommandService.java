package com.campusoverflow.qa.application.command;

import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.qa.api.event.AnswerAcceptedEvent;
import com.campusoverflow.qa.api.event.AnswerEndorsedEvent;
import com.campusoverflow.qa.api.event.AnswerSubmittedEvent;
import com.campusoverflow.qa.api.event.ContentDeletedEvent;
import com.campusoverflow.qa.application.port.QuestionListCache;
import com.campusoverflow.qa.domain.Answer;
import com.campusoverflow.qa.domain.AnswerRepository;
import com.campusoverflow.qa.domain.MarkdownBody;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.QuestionRepository;
import com.campusoverflow.shared.audit.AuditTrail;
import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.event.EventIds;
import com.campusoverflow.shared.event.IntegrationEventPublisher;
import com.campusoverflow.shared.security.Actor;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用例：提交 / 编辑 / 删除回答，采纳最佳答案，教师认证。 */
@Service
public class AnswerCommandService {

    private final QuestionRepository questions;
    private final AnswerRepository answers;
    private final IdentityApi identity;
    private final IntegrationEventPublisher events;
    private final QuestionListCache listCache;
    private final AuditTrail audit;
    private final Clock clock;

    public AnswerCommandService(QuestionRepository questions, AnswerRepository answers, IdentityApi identity,
                                IntegrationEventPublisher events, QuestionListCache listCache, AuditTrail audit,
                                Clock clock) {
        this.questions = questions;
        this.answers = answers;
        this.identity = identity;
        this.events = events;
        this.listCache = listCache;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public long submit(Actor actor, long questionId, String body) {
        // 不过滤已删除的问题：由 Question.ensureOpenForAnswers() 抛 QA-2005（422），
        // 而非 404——「已删除的问题不能再回答」是业务规则而不是寻址失败。
        Question question = loadQuestion(questionId);
        MarkdownBody markdown = MarkdownBody.ofAnswer(body);
        Instant now = clock.instant();

        Answer saved = answers.save(Answer.submit(question, actor.userId(), markdown, now));
        // 回答数是反规范化计数器，用原子增量维护，避免与并发提交互相覆盖。
        questions.adjustAnswerCount(question.id(), 1);
        events.publish(new AnswerSubmittedEvent(EventIds.next(), now, saved.id(), question.id(),
                saved.authorId(), question.authorId(), question.title().value()));
        listCache.invalidateAll();
        return saved.id();
    }

    @Transactional
    public void edit(Actor actor, long answerId, String body) {
        Answer answer = loadActiveAnswer(answerId);
        Question question = loadQuestion(answer.questionId());
        // 「已被采纳的回答不能改」的唯一事实来源是 Question.acceptedAnswerId，
        // 因此该判定只能在应用层做（领域 Answer 不持有采纳状态）。
        if (question.isAccepted(answerId)) {
            throw new BusinessRuleException("QA-2006", "回答已被采纳，不能编辑");
        }
        boolean actorManagesCourse = identity.canManageCourse(actor.userId(), question.courseId());

        answer.edit(actor, actorManagesCourse, MarkdownBody.ofAnswer(body), clock.instant());
        answers.save(answer);
        listCache.invalidateAll();
    }

    @Transactional
    public void delete(Actor actor, long answerId) {
        Answer answer = loadActiveAnswer(answerId);
        Question question = loadQuestion(answer.questionId());
        boolean actorManagesCourse = identity.canManageCourse(actor.userId(), question.courseId());
        Instant now = clock.instant();

        answer.softDelete(actor, actorManagesCourse, question.isAccepted(answerId), now);
        answers.save(answer);

        questions.adjustAnswerCount(question.id(), -1);
        events.publish(new ContentDeletedEvent(EventIds.next(), now, "ANSWER", answer.id(), question.id()));
        listCache.invalidateAll();
        audit.record("ANSWER_DELETED", actor.userId(), "ANSWER", String.valueOf(answer.id()),
                "questionId=" + question.id());
    }

    /** 场景 R-2：采纳最佳答案。状态变更与 Outbox 事件在同一事务提交；声誉结算异步进行。 */
    @Transactional
    public void accept(Actor actor, long questionId, long answerId) {
        Question question = loadActiveQuestion(questionId);
        // 不过滤已删除的回答：由 Question.accept() 抛 QA-2003（422），保持与验收场景一致。
        Answer answer = loadAnswer(answerId);
        boolean actorManagesCourse = identity.canManageCourse(actor.userId(), question.courseId());
        Instant now = clock.instant();

        // 权限、重复采纳、跨问题采纳、已删除回答等不变量全部由聚合根判定。
        question.accept(answer, actor, actorManagesCourse);
        questions.save(question);

        events.publish(new AnswerAcceptedEvent(EventIds.next(), now, answer.id(), question.id(),
                answer.authorId(), question.authorId(), actor.userId(), question.title().value()));
        listCache.invalidateAll();
        audit.record("ANSWER_ACCEPTED", actor.userId(), "ANSWER", String.valueOf(answer.id()),
                "questionId=" + question.id());
    }

    @Transactional
    public void endorse(Actor actor, long answerId) {
        Answer answer = loadActiveAnswer(answerId);
        Question question = loadQuestion(answer.questionId());
        boolean actorManagesCourse = identity.canManageCourse(actor.userId(), question.courseId());
        Instant now = clock.instant();

        answer.endorse(actor, actorManagesCourse);
        answers.save(answer);

        events.publish(new AnswerEndorsedEvent(EventIds.next(), now, answer.id(), question.id(),
                answer.authorId(), actor.userId(), question.title().value()));
        listCache.invalidateAll();
    }

    private Question loadQuestion(long id) {
        return questions.findById(id)
                .orElseThrow(() -> new NotFoundException("QA-4001", "问题不存在"));
    }

    private Question loadActiveQuestion(long id) {
        Question question = loadQuestion(id);
        if (question.isDeleted()) {
            throw new NotFoundException("QA-4001", "问题不存在或已被删除");
        }
        return question;
    }

    private Answer loadAnswer(long id) {
        return answers.findById(id)
                .orElseThrow(() -> new NotFoundException("QA-4002", "回答不存在"));
    }

    private Answer loadActiveAnswer(long id) {
        Answer answer = loadAnswer(id);
        if (answer.isDeleted()) {
            throw new NotFoundException("QA-4002", "回答不存在或已被删除");
        }
        return answer;
    }
}
