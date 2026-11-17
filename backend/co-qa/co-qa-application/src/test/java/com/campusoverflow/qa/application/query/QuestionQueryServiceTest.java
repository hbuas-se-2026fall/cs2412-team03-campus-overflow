package com.campusoverflow.qa.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.qa.application.port.ContentRenderer;
import com.campusoverflow.qa.application.port.QaReadModel;
import com.campusoverflow.qa.application.port.QuestionListCache;
import com.campusoverflow.qa.application.port.ViewCounter;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 读路径（CQRS-lite 查询用例）契约测试。此前 QuestionQueryService 78 行完全未被任何测试执行，
 * 而它承载了缓存、批量查作者避免 N+1、Markdown 摘要、投票态、采纳标记与权限投影等真实分支。
 * 只 mock 端口接口，不需要 Spring 容器和数据库。
 */
@ExtendWith(MockitoExtension.class)
class QuestionQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T02:00:00Z");
    private static final long QUESTION_ID = 10L;
    private static final long AUTHOR_ID = 7L;

    @Mock private QaReadModel readModel;
    @Mock private IdentityApi identity;
    @Mock private ContentRenderer renderer;
    @Mock private ViewCounter viewCounter;
    @Mock private QuestionListCache cache;

    private QuestionQueryService service;

    @BeforeEach
    void setUp() {
        service = new QuestionQueryService(readModel, identity, renderer, viewCounter, cache);
    }

    @Test
    void listReturnsCachedPageWithoutHittingReadModel() {
        PageResult<QuestionSummaryView> cached = PageResult.empty(1, 20);
        when(cache.get(3L, new PageRequest(1, 20))).thenReturn(Optional.of(cached));

        assertThat(service.list(3L, new PageRequest(1, 20))).isSameAs(cached);
        verifyNoInteractions(readModel, identity, renderer);
        verify(cache, never()).put(any(), any(), any());
    }

    @Test
    void listAssemblesSummaryViewsAndWritesCache() {
        PageRequest page = new PageRequest(1, 20);
        QuestionRow accepted = questionRow(QUESTION_ID, AUTHOR_ID, QUESTION_ID + 1);
        QuestionRow open = questionRow(11L, 8L, null);
        when(cache.get(3L, page)).thenReturn(Optional.empty());
        when(readModel.listQuestions(3L, page)).thenReturn(new PageResult<>(List.of(accepted, open), 2, 1, 20));
        when(identity.findUsers(any())).thenReturn(Map.of(
                AUTHOR_ID, user(AUTHOR_ID, "张三", Role.STUDENT),
                8L, user(8L, "李四", Role.TEACHER)));
        when(renderer.toPlainText(any(), eq(160))).thenReturn("摘要");

        PageResult<QuestionSummaryView> result = service.list(3L, page);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).hasSize(2);
        QuestionSummaryView first = result.items().get(0);
        assertThat(first.id()).isEqualTo(QUESTION_ID);
        assertThat(first.excerpt()).isEqualTo("摘要");
        assertThat(first.accepted()).isTrue();
        assertThat(first.author().displayName()).isEqualTo("张三");
        assertThat(first.author().role()).isEqualTo("STUDENT");
        assertThat(result.items().get(1).accepted()).isFalse();
        verify(cache).put(3L, page, result);
        verify(identity).findUsers(org.mockito.ArgumentMatchers.argThat(ids -> ids.size() == 2));
    }

    @Test
    void listFallsBackToDeregisteredAuthorWhenIdentityDoesNotKnowTheUser() {
        PageRequest page = new PageRequest(1, 20);
        when(cache.get(null, page)).thenReturn(Optional.empty());
        when(readModel.listQuestions(null, page))
                .thenReturn(new PageResult<>(List.of(questionRow(QUESTION_ID, 999L, null)), 1, 1, 20));
        when(identity.findUsers(any())).thenReturn(Map.of());
        when(renderer.toPlainText(any(), anyInt())).thenReturn("x");

        AuthorView author = service.list(null, page).items().get(0).author();

        assertThat(author.id()).isEqualTo(999L);
        assertThat(author.displayName()).isEqualTo("已注销用户");
        assertThat(author.role()).isEqualTo("STUDENT");
        assertThat(author.verified()).isFalse();
    }

    @Test
    void detailThrowsNotFoundAndDoesNotCountAView() {
        when(readModel.findQuestion(QUESTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(QUESTION_ID, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("问题不存在或已删除")
                .satisfies(ex -> assertThat(((NotFoundException) ex).code()).isEqualTo("QA-4001"));

        verifyNoInteractions(viewCounter);
    }

    @Test
    void detailForAnonymousVisitorSkipsVoteLookupAndGrantsNoPermissions() {
        when(readModel.findQuestion(QUESTION_ID)).thenReturn(Optional.of(questionRow(QUESTION_ID, AUTHOR_ID, null)));
        when(identity.findUser(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "张三", Role.STUDENT)));
        when(readModel.findAnswers(QUESTION_ID)).thenReturn(List.of(answerRow(50L, 8L, null)));
        when(identity.findUser(8L)).thenReturn(Optional.empty());
        when(renderer.toSafeHtml(any())).thenReturn("<p>safe</p>");

        QuestionDetailView view = service.detail(QUESTION_ID, null);

        verify(viewCounter).increment(QUESTION_ID);
        verify(readModel, never()).findMyVotes(anyLong(), anyLong(), any());
        assertThat(view.myVote()).isZero();
        assertThat(view.answers()).hasSize(1);
        assertThat(view.answers().get(0).myVote()).isZero();
        assertThat(view.answers().get(0).canEdit()).isFalse();
        assertThat(view.answers().get(0).author().displayName()).isEqualTo("已注销用户");
        assertThat(view.course()).isNotNull();
        assertThat(view.permissions().canEdit()).isFalse();
        assertThat(view.permissions().canDelete()).isFalse();
        assertThat(view.permissions().canAccept()).isFalse();
        assertThat(view.comments()).isEmpty();
    }

    @Test
    void detailForAuthorMarksAcceptedAnswerEndorsementAndVotes() {
        Actor author = new Actor(AUTHOR_ID, "张三", Role.STUDENT);
        when(readModel.findQuestion(QUESTION_ID))
                .thenReturn(Optional.of(questionRow(QUESTION_ID, AUTHOR_ID, 50L)));
        when(identity.findUser(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "张三", Role.STUDENT)));
        when(identity.findUser(8L)).thenReturn(Optional.of(user(8L, "李四", Role.TEACHER)));
        when(identity.findUser(9L)).thenReturn(Optional.of(user(9L, "王老师", Role.TEACHER)));
        when(readModel.findAnswers(QUESTION_ID)).thenReturn(List.of(answerRow(50L, 8L, 9L)));
        when(readModel.findMyVotes(AUTHOR_ID, QUESTION_ID, List.of(50L)))
                .thenReturn(Map.of("QUESTION:10", 1, "ANSWER:50", -1));
        when(renderer.toSafeHtml(any())).thenReturn("<p>safe</p>");

        QuestionDetailView view = service.detail(QUESTION_ID, author);

        assertThat(view.myVote()).isEqualTo(1);
        assertThat(view.acceptedAnswerId()).isEqualTo(50L);
        assertThat(view.answers().get(0).accepted()).isTrue();
        assertThat(view.answers().get(0).myVote()).isEqualTo(-1);
        assertThat(view.answers().get(0).endorsedBy().displayName()).isEqualTo("王老师");
        assertThat(view.answers().get(0).canEdit()).isFalse();
        assertThat(view.permissions().canEdit()).isTrue();
        assertThat(view.permissions().canDelete()).isTrue();
        assertThat(view.permissions().canAccept()).isTrue();
    }

    @Test
    void detailLetsTeacherAcceptOtherPeoplesQuestion() {
        Actor teacher = new Actor(99L, "李老师", Role.TEACHER);
        when(readModel.findQuestion(QUESTION_ID)).thenReturn(Optional.of(questionRow(QUESTION_ID, AUTHOR_ID, null)));
        when(identity.findUser(AUTHOR_ID)).thenReturn(Optional.empty());
        when(readModel.findAnswers(QUESTION_ID)).thenReturn(List.of());
        when(readModel.findMyVotes(99L, QUESTION_ID, List.of())).thenReturn(Map.of());
        when(renderer.toSafeHtml(any())).thenReturn("html");

        QuestionDetailView view = service.detail(QUESTION_ID, teacher);

        assertThat(view.permissions().canEdit()).isFalse();
        assertThat(view.permissions().canAccept()).isTrue();
        assertThat(view.bodyHtml()).isEqualTo("html");
    }

    @Test
    void detailWithoutCourseRefLeavesCourseNull() {
        QuestionRow row = new QuestionRow(QUESTION_ID, AUTHOR_ID, null, "标题", "正文", List.of(), null,
                0, 0, 0, NOW, NOW);
        when(readModel.findQuestion(QUESTION_ID)).thenReturn(Optional.of(row));
        when(identity.findUser(AUTHOR_ID)).thenReturn(Optional.empty());
        when(readModel.findAnswers(QUESTION_ID)).thenReturn(List.of());
        when(renderer.toSafeHtml(any())).thenReturn("html");

        assertThat(service.detail(QUESTION_ID, null).course()).isNull();
    }

    @Test
    void popularTagsClampsLimitIntoOneToFifty() {
        when(readModel.popularTags(anyInt())).thenReturn(List.of(new TagView("java", 3L)));

        assertThat(service.popularTags(0)).hasSize(1);
        verify(readModel).popularTags(1);

        service.popularTags(999);
        verify(readModel).popularTags(50);

        service.popularTags(7);
        verify(readModel).popularTags(7);
    }

    private static QuestionRow questionRow(long id, long authorId, Long acceptedAnswerId) {
        return new QuestionRow(id, authorId, 3L, "标题", "这是一段足够长的正文用于生成摘要",
                List.of("java", "spring"), acceptedAnswerId, 5, 2, 42, NOW, NOW);
    }

    private static AnswerRow answerRow(long id, long authorId, Long endorsedBy) {
        return new AnswerRow(id, authorId, "回答正文", 3, endorsedBy, NOW, NOW);
    }

    private static UserSummary user(long id, String name, Role role) {
        return new UserSummary(id, name, role, true, "计算机工程学院");
    }
}
