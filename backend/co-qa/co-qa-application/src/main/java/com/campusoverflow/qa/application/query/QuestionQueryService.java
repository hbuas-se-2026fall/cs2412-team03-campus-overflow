package com.campusoverflow.qa.application.query;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 问答查询用例（读路径）。 */
@Service
@Transactional(readOnly = true)
public class QuestionQueryService {

    private static final int EXCERPT_LENGTH = 160;

    private final QaReadModel readModel;
    private final IdentityApi identity;
    private final ContentRenderer renderer;
    private final ViewCounter viewCounter;
    private final QuestionListCache cache;

    public QuestionQueryService(QaReadModel readModel, IdentityApi identity, ContentRenderer renderer,
                                ViewCounter viewCounter, QuestionListCache cache) {
        this.readModel = readModel;
        this.identity = identity;
        this.renderer = renderer;
        this.viewCounter = viewCounter;
        this.cache = cache;
    }

    public PageResult<QuestionSummaryView> list(Long courseId, PageRequest page) {
        // 1. 查缓存
        var cached = cache.get(courseId, page);
        if (cached.isPresent()) {
            return cached.get();
        }

        // 2. 调用读模型获取分页数据
        PageResult<QuestionRow> pageResult = readModel.listQuestions(courseId, page);

        // 3. 提取所有作者 ID，批量获取作者信息
        Set<Long> authorIds = pageResult.items().stream()
                .map(QuestionRow::authorId)
                .collect(Collectors.toSet());
        Map<Long, UserSummary> userMap = identity.findUsers(authorIds);

        // 4. 组装 View 列表
        List<QuestionSummaryView> views = pageResult.items().stream().map(row -> {
            UserSummary user = userMap.get(row.authorId());
            AuthorView authorView = AuthorView.of(row.authorId(), user);

            String excerpt = renderer.toPlainText(row.body(), EXCERPT_LENGTH);

            return new QuestionSummaryView(
                    row.id(),
                    row.title(),
                    excerpt,
                    row.tags(),
                    row.courseId(),
                    authorView,
                    row.score(),
                    row.answerCount(),
                    row.viewCount(),
                    row.acceptedAnswerId() != null,
                    row.createdAt()
            );
        }).toList();

        PageResult<QuestionSummaryView> result = new PageResult<>(
                views, pageResult.total(), pageResult.page(), pageResult.size()
        );

        // 5. 写入缓存
        cache.put(courseId, page, result);

        return result;
    }

    public QuestionDetailView detail(long questionId, Actor actorOrNull) {
        // 1. 查详情
        QuestionRow row = readModel.findQuestion(questionId)
                .orElseThrow(() -> new NotFoundException("QA-4001", "问题不存在或已删除"));

        // 2. 增加浏览量
        viewCounter.increment(questionId);

        // 3. 查作者
        UserSummary user = identity.findUser(row.authorId()).orElse(null);
        AuthorView authorView = AuthorView.of(row.authorId(), user);

        // 4. 查回答列表
        List<AnswerRow> answerRows = readModel.findAnswers(questionId);

        // 5. 查投票状态（如果有用户登录）
        Map<String, Integer> myVotes = Collections.emptyMap();
        if (actorOrNull != null) {
            List<Long> answerIds = answerRows.stream().map(AnswerRow::id).toList();
            myVotes = readModel.findMyVotes(actorOrNull.userId(), questionId, answerIds);
        }
        final Map<String, Integer> finalMyVotes = myVotes;

        // 组装回答列表 (这里沿用之前的逻辑，用到了安全的 Renderer)
        List<AnswerView> answers = answerRows.stream().map(ans -> {
            AuthorView ansAuthor = AuthorView.of(ans.authorId(), identity.findUser(ans.authorId()).orElse(null));
            String bodyHtml = renderer.toSafeHtml(ans.body());
            boolean accepted = row.acceptedAnswerId() != null && row.acceptedAnswerId().equals(ans.id());

            AuthorView endorsedByView = null;
            if (ans.endorsedBy() != null) {
                endorsedByView = AuthorView.of(ans.endorsedBy(), identity.findUser(ans.endorsedBy()).orElse(null));
            }

            int ansMyVote = finalMyVotes.getOrDefault("ANSWER:" + ans.id(), 0);
            boolean canEditAns = actorOrNull != null && actorOrNull.userId() == ans.authorId();

            return new AnswerView(
                    ans.id(), ans.body(), bodyHtml, ansAuthor, ans.score(),
                    accepted, endorsedByView, ansMyVote, canEditAns,
                    ans.createdAt(), ans.updatedAt(), List.of()
            );
        }).toList();

        // ================= 下面是针对 QuestionDetailView 源码的组装 =================

        // 6. 构造 CourseRefView (假设如果没有课程关联，就传 null)
        CourseRefView courseRef = row.courseId() != null ? new CourseRefView(row.courseId(), "课程名", "2026") : null;
        // (注意：如果 CourseRefView 构造参数报错，按 Alt+Enter 看看它需要什么)

        // 7. 构造 PermissionsView
        // 简单逻辑：作者能编辑/删除；如果只有作者能采纳，按需调整
        boolean isAuthor = actorOrNull != null && actorOrNull.userId() == row.authorId();
        PermissionsView permissions = new PermissionsView(
                isAuthor, // canEdit
                isAuthor, // canDelete
                isAuthor || (actorOrNull != null && actorOrNull.role().name().equals("TEACHER")), // canAccept
                actorOrNull != null // canVote
        );
        // (注意：按 Alt+Enter 看看 PermissionsView 的构造器到底需要几个 boolean)

        // 8. 查评论列表 (如果你们还没写查评论逻辑，先用空列表占位)
        List<CommentView> comments = List.of();
        // (如果 readModel 有 findComments 可以换成查库)

        // 9. 当前用户对问题的投票
        int myVoteOnQuestion = finalMyVotes.getOrDefault("QUESTION:" + questionId, 0);

        // 10. 最终组装 (严格按照你发的 record 字段顺序)
        return new QuestionDetailView(
                row.id(),
                row.title(),
                row.body(),
                renderer.toSafeHtml(row.body()),
                row.tags(),
                courseRef,               // 对应 CourseRefView course
                authorView,
                row.score(),
                row.answerCount(),
                row.viewCount(),
                row.acceptedAnswerId(),  // 对应 Long acceptedAnswerId
                myVoteOnQuestion,        // 对应 int myVote
                row.createdAt(),
                row.updatedAt(),         // 对应 Instant updatedAt
                permissions,             // 对应 PermissionsView
                comments,                // 对应 List<CommentView>
                answers                  // 对应 List<AnswerView>
        );
    }

    public List<TagView> popularTags(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return readModel.popularTags(safeLimit);
    }
}