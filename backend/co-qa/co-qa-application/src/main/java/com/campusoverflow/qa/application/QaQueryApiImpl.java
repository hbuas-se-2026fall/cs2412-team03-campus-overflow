package com.campusoverflow.qa.application;

import com.campusoverflow.qa.api.QaQueryApi;
import com.campusoverflow.qa.api.QuestionRef;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.QuestionRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class QaQueryApiImpl implements QaQueryApi {

    private final QuestionRepository questions;

    public QaQueryApiImpl(QuestionRepository questions) {
        this.questions = questions;
    }

    /**
     * 其他上下文需要的「最少信息」：作者、所属课程、标题、是否已采纳、是否已删除。
     * 已软删除的问题也返回（由调用方按 {@code deleted} 自行判断），
     * 否则调用方无法区分「问题不存在」与「问题已删除」。
     */
    @Override
    public Optional<QuestionRef> findQuestion(long questionId) {
        if (questionId <= 0) {
            return Optional.empty();
        }
        return questions.findById(questionId).map(QaQueryApiImpl::toRef);
    }

    private static QuestionRef toRef(Question question) {
        return new QuestionRef(question.id(), question.authorId(), question.courseId(), question.title().value(),
                question.acceptedAnswerId(), question.isDeleted());
    }
}
