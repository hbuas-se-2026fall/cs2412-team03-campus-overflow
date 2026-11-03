package com.campusoverflow.qa.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.qa.api.event.CommentPostedEvent;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryAnswers;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryComments;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryQuestions;
import com.campusoverflow.qa.application.fake.QaTestFixtures.RecordingEvents;
import com.campusoverflow.qa.domain.Answer;
import com.campusoverflow.qa.domain.Comment;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 评论用例：只验证编排（目标解析、所属问题归属、事件），层级与归属规则由 VoteAndCommentTest 覆盖。 */
class CommentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T06:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryQuestions questions = new InMemoryQuestions();
    private final InMemoryAnswers answers = new InMemoryAnswers();
    private final InMemoryComments comments = new InMemoryComments();
    private final RecordingEvents events = new RecordingEvents();

    private final Actor ming = new Actor(1, "小明", Role.STUDENT);
    private final Actor hong = new Actor(2, "小红", Role.STUDENT);

    private CommentService service;
    private long questionId;
    private long answerId;

    @BeforeEach
    void setUp() {
        service = new CommentService(comments, questions, answers, events, clock);
        Question q = questions.save(Question.post(ming.userId(), 100L, new Title("红黑树插入后为什么必须旋转？"),
                MarkdownBody.ofQuestion("变色为什么不够？希望有直观解释，最好能给出一个反例。"),
                Tags.of(List.of("数据结构")), NOW));
        questionId = q.id();
        answerId = answers.save(Answer.submit(q, hong.userId(),
                MarkdownBody.ofAnswer("关键在于黑高这个不变量，仅靠变色无法调整。"), NOW)).id();
    }

    @Test
    @DisplayName("在问题下评论：父评论为空，事件带上问题 ID")
    void commentOnQuestion() {
        long id = service.post(ming, new PostCommentCommand(TargetType.QUESTION, questionId, null,
                "这门课的评分标准在哪里看？"));

        Comment saved = comments.findById(id).orElseThrow();
        assertThat(saved.parentId()).isNull();
        assertThat(saved.questionId()).isEqualTo(questionId);
        assertThat(saved.authorId()).isEqualTo(ming.userId());

        List<CommentPostedEvent> posted = events.ofType(CommentPostedEvent.class);
        assertThat(posted).hasSize(1);
        assertThat(posted.get(0).questionId()).isEqualTo(questionId);
        assertThat(posted.get(0).commentId()).isEqualTo(id);
    }

    @Test
    @DisplayName("回复回答下的评论：所属问题取回答的 questionId，而不是父评论 ID")
    void replyUnderAnswerCarriesQuestionId() {
        long parentId = service.post(hong, new PostCommentCommand(TargetType.ANSWER, answerId, null,
                "请问第二步为什么要变色？"));

        long replyId = service.post(ming, new PostCommentCommand(TargetType.ANSWER, answerId, parentId,
                "因为要保持黑高一致。"));

        Comment reply = comments.findById(replyId).orElseThrow();
        assertThat(reply.parentId()).isEqualTo(parentId);
        assertThat(reply.questionId()).isEqualTo(questionId);
        assertThat(events.ofType(CommentPostedEvent.class)).hasSize(2);
    }

    @Test
    @DisplayName("不支持二层以上的回复")
    void rejectsSecondLevelReply() {
        long top = service.post(hong, new PostCommentCommand(TargetType.ANSWER, answerId, null, "第一层评论内容。"));
        long reply = service.post(ming, new PostCommentCommand(TargetType.ANSWER, answerId, top, "第二层回复内容。"));

        assertThatThrownBy(() -> service.post(hong,
                new PostCommentCommand(TargetType.ANSWER, answerId, reply, "第三层不该被允许。")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("父评论必须挂在同一目标下")
    void parentMustShareTarget() {
        long parentId = service.post(hong, new PostCommentCommand(TargetType.ANSWER, answerId, null, "挂在回答下的评论。"));

        assertThatThrownBy(() -> service.post(ming,
                new PostCommentCommand(TargetType.QUESTION, questionId, parentId, "挂到问题下就跨目标了。")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("空评论被拒绝")
    void rejectsBlankBody() {
        assertThatThrownBy(() -> service.post(ming,
                new PostCommentCommand(TargetType.QUESTION, questionId, null, "   \n\t  ")))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(events.published).isEmpty();
    }

    @Test
    @DisplayName("被评论的内容不存在时返回 404")
    void targetMustExist() {
        assertThatThrownBy(() -> service.post(ming,
                new PostCommentCommand(TargetType.ANSWER, 999L, null, "给不存在的回答留言。")))
                .isInstanceOf(NotFoundException.class);
    }
}
