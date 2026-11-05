package com.campusoverflow.qa.application.command;

import com.campusoverflow.qa.api.event.VoteCastEvent;
import com.campusoverflow.qa.domain.Answer;
import com.campusoverflow.qa.domain.AnswerRepository;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.QuestionRepository;
import com.campusoverflow.qa.domain.TargetType;
import com.campusoverflow.qa.domain.Vote;
import com.campusoverflow.qa.domain.VoteRepository;
import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.event.EventIds;
import com.campusoverflow.shared.event.IntegrationEventPublisher;
import com.campusoverflow.shared.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用例：投票 / 改投 / 撤销（value ∈ {-1, 0, 1}，0 表示撤销）。
 * 发布的事件同时携带旧值与新值，下游据此计算声誉增量。
 */
@Service
public class VoteService {

    private final VoteRepository votes;
    private final QuestionRepository questions;
    private final AnswerRepository answers;
    private final IntegrationEventPublisher events;
    private final Clock clock;

    public VoteService(VoteRepository votes, QuestionRepository questions, AnswerRepository answers,
                       IntegrationEventPublisher events, Clock clock) {
        this.votes = votes;
        this.questions = questions;
        this.answers = answers;
        this.events = events;
        this.clock = clock;
    }

    public record VoteResult(int myVote, int scoreDelta) {
    }

    private record Target(long authorId, long questionId) {
    }

    /**
     * 幂等 PUT：同一票值重复提交不产生增量，也不重复发事件；0 表示撤销（删除票记录）。
     * 增量按「新值 − 旧值」计算，因此改投 +1 → -1 得到 -2 而不是 -1，撤销同理。
     */
    @Transactional
    public VoteResult cast(Actor actor, TargetType type, long targetId, int value) {
        if (value != -1 && value != 0 && value != 1) {
            throw new BusinessRuleException("QA-1016", "投票值只能是 +1、-1，或 0 表示撤销");
        }
        Instant now = clock.instant();
        Target target = resolve(type, targetId);
        if (actor.is(target.authorId())) {
            throw new BusinessRuleException("QA-1015", "不能给自己的内容投票");
        }

        Optional<Vote> existing = votes.find(actor.userId(), type, targetId);

        if (value == 0) {
            if (existing.isEmpty()) {
                return new VoteResult(0, 0);
            }
            Vote vote = existing.get();
            int previous = vote.value();
            votes.delete(vote);
            adjustScore(type, targetId, -previous);
            publish(now, type, targetId, target, actor, previous, 0);
            return new VoteResult(0, -previous);
        }

        if (existing.isPresent()) {
            Vote vote = existing.get();
            if (vote.value() == value) {
                return new VoteResult(value, 0);
            }
            int previous = vote.change(value, now);
            votes.save(vote);
            int delta = value - previous;
            adjustScore(type, targetId, delta);
            publish(now, type, targetId, target, actor, previous, value);
            return new VoteResult(value, delta);
        }

        Vote created = Vote.cast(actor.userId(), target.authorId(), type, targetId, value, now);
        votes.save(created);
        adjustScore(type, targetId, value);
        publish(now, type, targetId, target, actor, 0, value);
        return new VoteResult(value, value);
    }

    /** 解析被投内容：返回作者（自投票判定与声誉结算需要）与所属问题（事件与索引需要）。 */
    private Target resolve(TargetType type, long targetId) {
        if (type == TargetType.QUESTION) {
            Question question = questions.findById(targetId)
                    .orElseThrow(() -> new NotFoundException("QA-4001", "问题不存在"));
            if (question.isDeleted()) {
                throw new NotFoundException("QA-4001", "问题不存在或已被删除");
            }
            return new Target(question.authorId(), question.id());
        }
        Answer answer = answers.findById(targetId)
                .orElseThrow(() -> new NotFoundException("QA-4002", "回答不存在"));
        if (answer.isDeleted()) {
            throw new NotFoundException("QA-4002", "回答不存在或已被删除");
        }
        return new Target(answer.authorId(), answer.questionId());
    }

    /** 分数是反规范化计数器，用原子增量维护；增量为 0 时不发 SQL。 */
    private void adjustScore(TargetType type, long targetId, int delta) {
        if (delta == 0) {
            return;
        }
        if (type == TargetType.QUESTION) {
            questions.adjustScore(targetId, delta);
        } else {
            answers.adjustScore(targetId, delta);
        }
    }

    private void publish(Instant now, TargetType type, long targetId, Target target, Actor actor,
                         int oldValue, int newValue) {
        events.publish(new VoteCastEvent(EventIds.next(), now, type.name(), targetId, target.authorId(),
                actor.userId(), target.questionId(), oldValue, newValue));
    }
}
