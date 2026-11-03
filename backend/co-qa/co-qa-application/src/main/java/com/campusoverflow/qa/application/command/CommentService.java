package com.campusoverflow.qa.application.command;

import com.campusoverflow.qa.api.event.CommentPostedEvent;
import com.campusoverflow.qa.domain.Answer;
import com.campusoverflow.qa.domain.AnswerRepository;
import com.campusoverflow.qa.domain.Comment;
import com.campusoverflow.qa.domain.CommentRepository;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.QuestionRepository;
import com.campusoverflow.qa.domain.TargetType;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.event.EventIds;
import com.campusoverflow.shared.event.IntegrationEventPublisher;
import com.campusoverflow.shared.security.Actor;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用例：评论与二级回复。评论中的 @昵称 由 Discovery 解析并发送提醒。 */
@Service
public class CommentService {

    private final CommentRepository comments;
    private final QuestionRepository questions;
    private final AnswerRepository answers;
    private final IntegrationEventPublisher events;
    private final Clock clock;

    public CommentService(CommentRepository comments, QuestionRepository questions, AnswerRepository answers,
                          IntegrationEventPublisher events, Clock clock) {
        this.comments = comments;
        this.questions = questions;
        this.answers = answers;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public long post(Actor actor, PostCommentCommand cmd) {
        TargetType type = cmd.targetType();
        long targetId = cmd.targetId();
        // 评论必须先挂在一个存在且未删除的内容上；同时解析出所属问题，供事件与通知使用。
        long questionId = resolveQuestionId(type, targetId);

        Comment parent = null;
        if (cmd.parentId() != null) {
            parent = comments.findById(cmd.parentId())
                    .orElseThrow(() -> new NotFoundException("QA-4003", "父评论不存在"));
        }

        Instant now = clock.instant();
        // 「只支持一层回复」「父评论必须同目标」由聚合根判定（QA-1017 / QA-1018）。
        Comment saved = comments.save(
                Comment.post(type, targetId, questionId, actor.userId(), parent, cmd.body(), now));

        events.publish(new CommentPostedEvent(EventIds.next(), now, saved.id(), questionId,
                actor.userId(), saved.body()));
        return saved.id();
    }

    /** 校验被评论内容存在且未删除，返回其所属问题 ID（评论问题本身时即为其自身 ID）。 */
    private long resolveQuestionId(TargetType type, long targetId) {
        if (type == TargetType.QUESTION) {
            Question question = questions.findById(targetId)
                    .orElseThrow(() -> new NotFoundException("QA-4001", "问题不存在"));
            if (question.isDeleted()) {
                throw new NotFoundException("QA-4001", "问题不存在或已被删除");
            }
            return question.id();
        }
        Answer answer = answers.findById(targetId)
                .orElseThrow(() -> new NotFoundException("QA-4002", "回答不存在"));
        if (answer.isDeleted()) {
            throw new NotFoundException("QA-4002", "回答不存在或已被删除");
        }
        return answer.questionId();
    }
}
