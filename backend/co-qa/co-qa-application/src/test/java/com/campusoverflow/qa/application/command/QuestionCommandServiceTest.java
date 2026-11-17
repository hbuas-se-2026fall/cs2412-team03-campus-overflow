package com.campusoverflow.qa.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.qa.api.event.ContentDeletedEvent;
import com.campusoverflow.qa.api.event.QuestionEditedEvent;
import com.campusoverflow.qa.api.event.QuestionPostedEvent;
import com.campusoverflow.qa.application.fake.QaTestFixtures.CountingCache;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryQuestions;
import com.campusoverflow.qa.application.fake.QaTestFixtures.RecordingAudit;
import com.campusoverflow.qa.application.fake.QaTestFixtures.RecordingEvents;
import com.campusoverflow.qa.application.fake.QaTestFixtures.RecordingTags;
import com.campusoverflow.qa.application.fake.QaTestFixtures.StubIdentity;
import com.campusoverflow.qa.application.fake.QaTestFixtures.StubRenderer;
import com.campusoverflow.qa.domain.MarkdownBody;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.Tags;
import com.campusoverflow.qa.domain.Title;
import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.ForbiddenException;
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

/** 应用服务用例测试：只验证编排逻辑（标签计数、事件、缓存、审计、404 语义），领域规则由 QuestionTest 覆盖。 */
class QuestionCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T06:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryQuestions questions = new InMemoryQuestions();
    private final RecordingTags tags = new RecordingTags();
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

    private QuestionCommandService service;

    @BeforeEach
    void setUp() {
        service = new QuestionCommandService(questions, tags, identity, events, new StubRenderer(), cache, audit, clock);
    }

    private PostQuestionCommand postCommand() {
        return new PostQuestionCommand(100L, "红黑树插入后如何判断旋转方向？",
                "插入节点后出现连续红色节点，什么时候左旋、什么时候右旋？", List.of("#数据结构", " 红黑树 "));
    }

    @Test
    @DisplayName("发布问题：标签规范化入库、使用计数 +1、发布 QuestionPosted 并失效列表缓存")
    void postPublishesEventAndBumpsTagUsage() {
        long id = service.post(ming, postCommand());

        Question saved = questions.findById(id).orElseThrow();
        assertThat(saved.authorId()).isEqualTo(ming.userId());
        assertThat(saved.tags().names()).containsExactly("数据结构", "红黑树");
        assertThat(tags.usages).containsEntry("数据结构", 1).containsEntry("红黑树", 1);

        List<QuestionPostedEvent> posted = events.ofType(QuestionPostedEvent.class);
        assertThat(posted).hasSize(1);
        assertThat(posted.get(0).questionId()).isEqualTo(id);
        assertThat(posted.get(0).authorId()).isEqualTo(ming.userId());
        assertThat(posted.get(0).tags()).containsExactly("数据结构", "红黑树");
        assertThat(posted.get(0).plainText()).startsWith("插入节点后");
        assertThat(cache.invalidations).isEqualTo(1);
    }

    @Test
    @DisplayName("发布问题：标题过短抛 QA-1001，且不落库、不计数")
    void postRejectsInvalidTitleBeforePersisting() {
        assertThatThrownBy(() -> service.post(ming,
                new PostQuestionCommand(100L, "太短了", "插入节点后出现连续红色节点，什么时候左旋？", List.of("红黑树"))))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(tags.usages).isEmpty();
        assertThat(events.published).isEmpty();
    }

    @Test
    @DisplayName("编辑问题：标签按差集调整计数，旧标签 -1、新标签 +1")
    void editAdjustsTagUsageByDifference() {
        long id = service.post(ming, postCommand());

        service.edit(ming, id, new EditQuestionCommand("红黑树插入后左右旋的判定条件是什么？",
                "补充：需要看叔叔节点的颜色，以及当前节点是父节点的左孩子还是右孩子。",
                List.of("数据结构", "红黑树", "java")));

        assertThat(tags.usages).containsEntry("java", 1).containsEntry("数据结构", 1);
        List<QuestionEditedEvent> edited = events.ofType(QuestionEditedEvent.class);
        assertThat(edited).hasSize(1);
        assertThat(edited.get(0).tags()).containsExactly("数据结构", "红黑树", "java");
        assertThat(questions.findById(id).orElseThrow().title().value()).contains("判定条件");
    }

    @Test
    @DisplayName("编辑问题：非作者且非课程管理者返回 403")
    void strangerCannotEdit() {
        long id = service.post(ming, postCommand());

        // 正文必须满足 MarkdownBody 的 20 字下限，否则会在构造值对象时先抛
        // BusinessRuleException，根本走不到聚合根的权限校验——断言就变成假绿了。
        assertThatThrownBy(() -> service.edit(hong, id,
                new EditQuestionCommand("红黑树插入后左右旋的判定条件是什么？",
                        "试图篡改正文的其他同学内容在这里，补充足够长度的描述以通过正文长度校验。",
                        List.of("红黑树"))))
                .isInstanceOf(ForbiddenException.class);
        assertThat(questions.findById(id).orElseThrow().title().value()).isEqualTo("红黑树插入后如何判断旋转方向？");
    }

    @Test
    @DisplayName("编辑问题：课程教师可以编辑学生的问题")
    void courseTeacherCanEdit() {
        long id = service.post(ming, postCommand());

        service.edit(teacher, id, new EditQuestionCommand("红黑树插入后左右旋的判定条件是什么？",
                "教师补充：先看叔叔节点颜色，再判断当前节点位于父节点的哪一侧。", List.of("数据结构")));

        assertThat(questions.findById(id).orElseThrow().title().value()).contains("判定条件");
    }

    @Test
    @DisplayName("删除问题：已有回答时提问者被拒（422），教师可删")
    void askerCannotDeleteAnsweredQuestion() {
        Question answered = Question.reconstitute(null, ming.userId(), 100L,
                new Title("一个已经有回答的问题标题示例"),
                MarkdownBody.ofQuestion("这个问题下面已经有一条回答了，所以提问者不能直接删除它。"),
                Tags.of(List.of("java")), null, 0, 1, 0, NOW, NOW, null, 0);
        long id = questions.save(answered).id();

        assertThatThrownBy(() -> service.delete(ming, id)).isInstanceOf(BusinessRuleException.class);
        assertThat(events.ofType(ContentDeletedEvent.class)).isEmpty();

        service.delete(teacher, id);
        assertThat(questions.findById(id).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("删除问题：软删除、标签计数 -1、发布 ContentDeleted 并写审计")
    void deletePublishesEventAndReleasesTags() {
        long id = service.post(ming, postCommand());

        service.delete(ming, id);

        assertThat(questions.findById(id).orElseThrow().isDeleted()).isTrue();
        assertThat(tags.usages).containsEntry("数据结构", 0).containsEntry("红黑树", 0);

        List<ContentDeletedEvent> deleted = events.ofType(ContentDeletedEvent.class);
        assertThat(deleted).hasSize(1);
        assertThat(deleted.get(0).targetType()).isEqualTo("QUESTION");
        assertThat(deleted.get(0).targetId()).isEqualTo(id);
        assertThat(audit.actions).contains("QUESTION_DELETED");
        assertThat(cache.invalidations).isEqualTo(2);
    }

    @Test
    @DisplayName("已删除的问题再编辑返回 404 而不是 500")
    void editingDeletedQuestionYieldsNotFound() {
        long id = service.post(ming, postCommand());
        service.delete(ming, id);

        assertThatThrownBy(() -> service.edit(ming, id,
                new EditQuestionCommand("红黑树插入后左右旋的判定条件是什么？", "删除之后还想改正文的尝试内容。",
                        List.of("红黑树"))))
                .isInstanceOf(NotFoundException.class);
    }
}
