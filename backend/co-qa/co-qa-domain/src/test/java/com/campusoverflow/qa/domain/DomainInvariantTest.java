package com.campusoverflow.qa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 领域层不变量与持久化往返的边界用例（Issue #31 的「核心聚合 90%+」与「没有无效断言」）。
 *
 * <p>这里补的不是凑行数的 getter 调用：reconstitute 是仓储从数据库还原聚合的入口，
 * 字段错位会直接导致数据写回时串列；assignId 重复分配、已删除聚合继续被操作，
 * 都是会产生脏数据的真实故障。每组断言都验证了具体的业务结果。
 */
class DomainInvariantTest {

    private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    private static final Actor ASKER = new Actor(1, "提问者", Role.STUDENT);
    private static final Actor OTHER = new Actor(2, "路人", Role.STUDENT);
    private static final Actor TEACHER = new Actor(9, "王老师", Role.TEACHER);

    private static Title title() {
        return new Title("红黑树插入后如何判断旋转方向？");
    }

    private static MarkdownBody body() {
        return MarkdownBody.ofQuestion("插入节点后出现连续红色节点，什么时候左旋、什么时候右旋？");
    }

    private static Tags tags() {
        return Tags.of(List.of("数据结构", "红黑树"));
    }

    @Test
    void questionReconstitutePreservesEveryFieldUsedByRepositoryWriteback() {
        Title t = title();
        MarkdownBody b = body();
        Tags tg = tags();
        Question q = Question.reconstitute(10L, 1L, 100L, t, b, tg, 50L, 7, 2, 33,
                NOW, NOW.plusSeconds(5), null, 3);

        assertThat(q.id()).isEqualTo(10L);
        assertThat(q.authorId()).isEqualTo(1L);
        assertThat(q.courseId()).isEqualTo(100L);
        assertThat(q.title()).isSameAs(t);
        assertThat(q.body()).isSameAs(b);
        assertThat(q.tags()).isSameAs(tg);
        assertThat(q.acceptedAnswerId()).isEqualTo(50L);
        assertThat(q.score()).isEqualTo(7);
        assertThat(q.answerCount()).isEqualTo(2);
        assertThat(q.viewCount()).isEqualTo(33);
        assertThat(q.createdAt()).isEqualTo(NOW);
        assertThat(q.updatedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(q.deletedAt()).isNull();
        assertThat(q.version()).isEqualTo(3);
        assertThat(q.isDeleted()).isFalse();
        assertThat(q.isAccepted(50L)).isTrue();
        assertThat(q.isAccepted(51L)).isFalse();
    }

    @Test
    void answerReconstitutePreservesEveryFieldUsedByRepositoryWriteback() {
        MarkdownBody b = MarkdownBody.ofAnswer("看叔叔节点的颜色和插入位置。");
        Instant deleted = NOW.plusSeconds(30);
        Answer a = Answer.reconstitute(50L, 10L, 3L, b, 12, 9L,
                NOW, NOW.plusSeconds(10), deleted, 4);

        assertThat(a.id()).isEqualTo(50L);
        assertThat(a.questionId()).isEqualTo(10L);
        assertThat(a.authorId()).isEqualTo(3L);
        assertThat(a.body()).isSameAs(b);
        assertThat(a.score()).isEqualTo(12);
        assertThat(a.endorsedBy()).isEqualTo(9L);
        assertThat(a.createdAt()).isEqualTo(NOW);
        assertThat(a.updatedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(a.deletedAt()).isEqualTo(deleted);
        assertThat(a.version()).isEqualTo(4);
        assertThat(a.isDeleted()).isTrue();
    }

    @Test
    void voteReconstituteAndAssignIdKeepIdentityIntact() {
        Vote v = Vote.reconstitute(7L, 100L, TargetType.QUESTION, 200L, -1, NOW);
        assertThat(v.id()).isEqualTo(7L);
        assertThat(v.voterId()).isEqualTo(100L);
        assertThat(v.targetType()).isEqualTo(TargetType.QUESTION);
        assertThat(v.targetId()).isEqualTo(200L);
        assertThat(v.value()).isEqualTo(-1);
        assertThat(v.updatedAt()).isEqualTo(NOW);

        Vote fresh = Vote.cast(100L, 999L, TargetType.ANSWER, 200L, 1, NOW);
        assertThat(fresh.id()).isNull();
        fresh.assignId(8L);
        assertThat(fresh.id()).isEqualTo(8L);

        Instant later = NOW.plusSeconds(60);
        assertThat(fresh.change(-1, later)).isEqualTo(1);
        assertThat(fresh.value()).isEqualTo(-1);
        assertThat(fresh.updatedAt()).isEqualTo(later);
    }

    @Test
    void voteRejectsZeroAndSelfVoteWithDistinctRules() {
        assertThatThrownBy(() -> Vote.cast(1, 3, TargetType.ANSWER, 50, 0, NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("投票值");
        assertThatThrownBy(() -> Vote.cast(3, 3, TargetType.ANSWER, 50, 1, NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("自己");
        assertThat(Vote.cast(1, 3, TargetType.ANSWER, 50, -1, NOW).value()).isEqualTo(-1);
    }

    @Test
    void commentReconstituteAndAssignIdGuard() {
        Comment c = Comment.reconstitute(5L, TargetType.ANSWER, 50L, 10L, 3L, 1L,
                "这是一条二级回复内容", NOW);
        assertThat(c.id()).isEqualTo(5L);
        assertThat(c.targetType()).isEqualTo(TargetType.ANSWER);
        assertThat(c.targetId()).isEqualTo(50L);
        assertThat(c.questionId()).isEqualTo(10L);
        assertThat(c.authorId()).isEqualTo(3L);
        assertThat(c.parentId()).isEqualTo(1L);
        assertThat(c.body()).isEqualTo("这是一条二级回复内容");
        assertThat(c.createdAt()).isEqualTo(NOW);

        Comment top = Comment.post(TargetType.ANSWER, 50, 10, 1, null, "顶层评论内容", NOW);
        assertThat(top.id()).isNull();
        assertThat(top.parentId()).isNull();
        top.assignId(1);
        assertThat(top.id()).isEqualTo(1L);
        assertThatThrownBy(() -> top.assignId(2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ID 已分配");
    }

    @Test
    void assignIdIsSingleShotOnQuestionAndAnswer() {
        Question q = Question.post(ASKER.userId(), 100L, title(), body(), tags(), NOW);
        q.assignId(10);
        assertThatThrownBy(() -> q.assignId(11))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ID 已分配");

        Answer a = Answer.submit(q, 3, MarkdownBody.ofAnswer("看叔叔节点的颜色和插入位置。"), NOW);
        a.assignId(50);
        assertThatThrownBy(() -> a.assignId(51))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ID 已分配");
    }

    @Test
    void answerAuthorCanEditBodyAndTimestampAdvances() {
        Question q = Question.post(ASKER.userId(), 100L, title(), body(), tags(), NOW);
        q.assignId(10);
        Answer a = Answer.submit(q, 3, MarkdownBody.ofAnswer("看叔叔节点的颜色和插入位置。"), NOW);
        a.assignId(50);

        MarkdownBody revised = MarkdownBody.ofAnswer("改投之后要重新计算声誉增量，正文也一并改写。");
        Instant later = NOW.plusSeconds(60);
        a.edit(new Actor(3, "回答者", Role.STUDENT), false, revised, later);

        assertThat(a.body()).isSameAs(revised);
        assertThat(a.updatedAt()).isEqualTo(later);
        assertThat(a.createdAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> a.edit(OTHER, false, revised, later))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void unpersistedQuestionCannotReceiveAnswer() {
        Question q = Question.post(ASKER.userId(), 100L, title(), body(), tags(), NOW);
        assertThat(q.id()).isNull();
        assertThatThrownBy(() -> Answer.submit(q, 3,
                MarkdownBody.ofAnswer("看叔叔节点的颜色和插入位置。"), NOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("尚未持久化");
    }

    @Test
    void unpersistedAnswerCannotBeAccepted() {
        Question q = Question.post(ASKER.userId(), 100L, title(), body(), tags(), NOW);
        q.assignId(10);
        Answer a = Answer.reconstitute(null, 10L, 3L,
                MarkdownBody.ofAnswer("看叔叔节点的颜色和插入位置。"), 0, null, NOW, NOW, null, 0);
        assertThatThrownBy(() -> q.accept(a, ASKER, false))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("尚未持久化");
        assertThat(q.acceptedAnswerId()).isNull();
    }

    @Test
    void acceptedQuestionCannotBeEdited() {
        Question q = Question.reconstitute(10L, ASKER.userId(), 100L, title(), body(), tags(),
                50L, 0, 1, 0, NOW, NOW, null, 0);
        assertThatThrownBy(() -> q.edit(ASKER, false,
                new Title("改一个全新的问题标题试试看"), body(), tags(), NOW.plusSeconds(1)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("已被采纳");
        assertThat(q.title().value()).isEqualTo("红黑树插入后如何判断旋转方向？");
    }

    @Test
    void strangerCannotDeleteQuestion() {
        Question q = Question.post(ASKER.userId(), 100L, title(), body(), tags(), NOW);
        q.assignId(10);
        assertThatThrownBy(() -> q.softDelete(OTHER, false, NOW))
                .isInstanceOf(ForbiddenException.class);
        assertThat(q.isDeleted()).isFalse();
    }

    @Test
    void deletedAggregatesRefuseFurtherMutations() {
        Question q = Question.post(ASKER.userId(), 100L, title(), body(), tags(), NOW);
        q.assignId(10);
        Answer a = Answer.submit(q, 3, MarkdownBody.ofAnswer("看叔叔节点的颜色和插入位置。"), NOW);
        a.assignId(50);

        q.softDelete(ASKER, false, NOW);
        assertThat(q.isDeleted()).isTrue();
        assertThatThrownBy(() -> q.edit(ASKER, false, title(), body(), tags(), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已删除");
        assertThatThrownBy(() -> q.accept(a, ASKER, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已删除");

        a.softDelete(new Actor(3, "回答者", Role.STUDENT), false, false, NOW);
        assertThat(a.isDeleted()).isTrue();
        assertThatThrownBy(() -> a.edit(new Actor(3, "回答者", Role.STUDENT), false,
                MarkdownBody.ofAnswer("改写之后的回答正文内容"), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已删除");
        assertThatThrownBy(() -> a.endorse(TEACHER, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已删除");
    }

    @Test
    void markdownBodyEnforcesBothLowerBounds() {
        assertThat(MarkdownBody.ofQuestion("x".repeat(20)).value()).hasSize(20);
        assertThat(MarkdownBody.ofAnswer("y".repeat(10)).value()).hasSize(10);
        assertThatThrownBy(() -> MarkdownBody.ofQuestion("x".repeat(19)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("20-30000");
        assertThatThrownBy(() -> MarkdownBody.ofAnswer("y".repeat(9)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("10-30000");
        assertThatThrownBy(() -> MarkdownBody.ofQuestion(null))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> MarkdownBody.ofAnswer(null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void tagNameRejectsNull() {
        assertThatThrownBy(() -> new TagName(null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("标签不能为空");
        assertThat(new TagName("#C++").value()).isEqualTo("c++");
    }

    @Test
    void tagsRejectBlankOnlyInputAndOverLimitAfterDeduplication() {
        assertThat(Tags.of(List.of("#Java", "java", " Spring-Boot ")).names())
                .containsExactly("java", "spring-boot");
        // List.of 不允许 null 元素，这里用 Arrays.asList 才能把「含 null 的原始输入」送进 Tags.of
        assertThatThrownBy(() -> Tags.of(java.util.Arrays.asList("", "  ", null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("1-5");
        assertThatThrownBy(() -> Tags.of(List.of("a", "b", "c", "d", "e", "f", "g")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("最多 5 个标签");
    }
}
