package com.campusoverflow.qa.application.command;

import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.qa.api.event.ContentDeletedEvent;
import com.campusoverflow.qa.api.event.QuestionEditedEvent;
import com.campusoverflow.qa.api.event.QuestionPostedEvent;
import com.campusoverflow.qa.application.port.ContentRenderer;
import com.campusoverflow.qa.application.port.QuestionListCache;
import com.campusoverflow.qa.domain.MarkdownBody;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.QuestionRepository;
import com.campusoverflow.qa.domain.TagRepository;
import com.campusoverflow.qa.domain.Tags;
import com.campusoverflow.qa.domain.Title;
import com.campusoverflow.shared.audit.AuditTrail;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.event.EventIds;
import com.campusoverflow.shared.event.IntegrationEventPublisher;
import com.campusoverflow.shared.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用例：发布 / 编辑 / 删除问题。 */
@Service
public class QuestionCommandService {

    static final int PLAIN_TEXT_FOR_INDEX = 2000;

    private final QuestionRepository questions;
    private final TagRepository tags;
    private final IdentityApi identity;
    private final IntegrationEventPublisher events;
    private final ContentRenderer renderer;
    private final QuestionListCache listCache;
    private final AuditTrail audit;
    private final Clock clock;

    public QuestionCommandService(QuestionRepository questions, TagRepository tags, IdentityApi identity,
                                  IntegrationEventPublisher events, ContentRenderer renderer,
                                  QuestionListCache listCache, AuditTrail audit, Clock clock) {
        this.questions = questions;
        this.tags = tags;
        this.identity = identity;
        this.events = events;
        this.renderer = renderer;
        this.listCache = listCache;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public long post(Actor actor, PostQuestionCommand cmd) {
        Instant now = clock.instant();
        // 值对象先行校验（QA-1001 / QA-1002 / QA-1005），非法输入不会落库。
        Title title = new Title(cmd.title());
        MarkdownBody body = MarkdownBody.ofQuestion(cmd.body());
        Tags tagValues = Tags.of(cmd.tags());

        Question saved = questions.save(
                Question.post(actor.userId(), cmd.courseId(), title, body, tagValues, now));

        tags.adjustUsage(tagValues.names(), 1);
        events.publish(new QuestionPostedEvent(EventIds.next(), now, saved.id(), saved.authorId(),
                saved.courseId(), saved.title().value(),
                renderer.toPlainText(body.value(), PLAIN_TEXT_FOR_INDEX), tagValues.names()));
        listCache.invalidateAll();
        return saved.id();
    }

    @Transactional
    public void edit(Actor actor, long questionId, EditQuestionCommand cmd) {
        Question question = load(questionId);
        boolean actorManagesCourse = identity.canManageCourse(actor.userId(), question.courseId());
        Instant now = clock.instant();

        Title title = new Title(cmd.title());
        MarkdownBody body = MarkdownBody.ofQuestion(cmd.body());
        Tags newTags = Tags.of(cmd.tags());
        List<String> previousTagNames = question.tags().names();

        // 权限、已采纳、已删除等不变量由聚合根守护。
        question.edit(actor, actorManagesCourse, title, body, newTags, now);
        questions.save(question);

        // 标签使用计数按差集调整，避免全量重算。
        if (!previousTagNames.equals(newTags.names())) {
            tags.adjustUsage(previousTagNames, -1);
            tags.adjustUsage(newTags.names(), 1);
        }
        events.publish(new QuestionEditedEvent(EventIds.next(), now, question.id(), title.value(),
                renderer.toPlainText(body.value(), PLAIN_TEXT_FOR_INDEX), newTags.names()));
        listCache.invalidateAll();
    }

    @Transactional
    public void delete(Actor actor, long questionId) {
        Question question = load(questionId);
        boolean actorManagesCourse = identity.canManageCourse(actor.userId(), question.courseId());
        Instant now = clock.instant();
        List<String> tagNames = question.tags().names();

        // 有回答时提问者不能删除，课程管理者可以——规则在聚合根内判定。
        question.softDelete(actor, actorManagesCourse, now);
        questions.save(question);

        tags.adjustUsage(tagNames, -1);
        events.publish(new ContentDeletedEvent(EventIds.next(), now, "QUESTION", question.id(), question.id()));
        listCache.invalidateAll();
        audit.record("QUESTION_DELETED", actor.userId(), "QUESTION", String.valueOf(question.id()), null);
    }

    /** 加载可操作的问题：不存在或已软删除均视为 404（错误码文档约定）。 */
    private Question load(long questionId) {
        Question question = questions.findById(questionId)
                .orElseThrow(() -> new NotFoundException("QA-4001", "问题不存在"));
        if (question.isDeleted()) {
            throw new NotFoundException("QA-4001", "问题不存在或已被删除");
        }
        return question;
    }
}
