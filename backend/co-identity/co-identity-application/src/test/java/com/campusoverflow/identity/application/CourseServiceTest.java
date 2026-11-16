package com.campusoverflow.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.identity.application.fake.IdentityTestFixtures;
import com.campusoverflow.identity.domain.CourseRole;
import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.ConflictException;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.security.Actor;
import org.junit.jupiter.api.Test;

/** 用例：课程与课程成员管理（建课 / 加入 / 任命助教 / 列表与成员视图）。 */
class CourseServiceTest {

    private static final long TEACHER_ID = 100L;
    private static final long OTHER_TEACHER_ID = 101L;
    private static final long STUDENT_ID = 200L;
    private static final long ADMIN_ID = 900L;

    private final IdentityTestFixtures.Users users = new IdentityTestFixtures.Users();
    private final IdentityTestFixtures.Courses courses = new IdentityTestFixtures.Courses();
    private final CourseService service = new CourseService(courses, users, IdentityTestFixtures.CLOCK);

    private static Actor teacher(long id) {
        return IdentityTestFixtures.teacher(id);
    }

    private static Actor student(long id) {
        return IdentityTestFixtures.student(id);
    }

    private static Actor admin(long id) {
        return IdentityTestFixtures.admin(id);
    }

    private static CreateCourseCommand course(String code, String name, String term) {
        return new CreateCourseCommand(code, name, term);
    }

    @Test
    void 教师建课成功并自动成为课程教师() {
        long id = service.create(teacher(TEACHER_ID), course("cs101", "数据结构与算法", "2026秋"));

        assertThat(id).isPositive();
        assertThat(courses.course(id)).isNotNull();
        assertThat(courses.course(id).code()).isEqualTo("CS101");
        assertThat(courses.course(id).name()).isEqualTo("数据结构与算法");
        assertThat(courses.course(id).teacherId()).isEqualTo(TEACHER_ID);
        assertThat(courses.member(id, TEACHER_ID).role()).isEqualTo(CourseRole.TEACHER);
    }

    @Test
    void 学生不能建课() {
        assertThatThrownBy(() -> service.create(student(STUDENT_ID), course("cs102", "操作系统", "2026秋")))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("ID-3002");
        assertThat(courses.findAll()).isEmpty();
    }

    @Test
    void 管理员也可以建课() {
        long id = service.create(admin(ADMIN_ID), course("cs103", "编译原理", "2026秋"));

        assertThat(courses.course(id).teacherId()).isEqualTo(ADMIN_ID);
    }

    @Test
    void 同学期同代码课程重复时抛ID2004且代码大小写不敏感() {
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);

        assertThatThrownBy(() -> service.create(teacher(TEACHER_ID), course("cs101", "数据结构与算法（重开）", "2026秋")))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("ID-2004");
    }

    @Test
    void 同学期不同代码可以建课() {
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);

        long id = service.create(teacher(TEACHER_ID), course("CS102", "操作系统", "2026秋"));

        assertThat(courses.course(id).code()).isEqualTo("CS102");
    }

    @Test
    void 课程代码格式非法时抛ID1010() {
        assertThatThrownBy(() -> service.create(teacher(TEACHER_ID), course("C", "课程名太短", "2026秋")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1010");
    }

    @Test
    void 加入课程成功写入学生成员关系() {
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);

        service.join(student(STUDENT_ID), 1L);

        assertThat(courses.member(1L, STUDENT_ID).role()).isEqualTo(CourseRole.STUDENT);
        assertThat(courses.member(1L, STUDENT_ID).courseId()).isEqualTo(1L);
    }

    @Test
    void 重复加入同一课程时抛ID2005() {
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);
        courses.givenMember(1L, STUDENT_ID, CourseRole.STUDENT);

        assertThatThrownBy(() -> service.join(student(STUDENT_ID), 1L))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("ID-2005");
    }

    @Test
    void 加入不存在的课程时抛ID4002() {
        assertThatThrownBy(() -> service.join(student(STUDENT_ID), 404L))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("ID-4002");
    }

    @Test
    void 课程教师可以把成员任命为助教() {
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);
        courses.givenMember(1L, STUDENT_ID, CourseRole.STUDENT);

        service.assignRole(teacher(TEACHER_ID), 1L, STUDENT_ID, CourseRole.TA);

        assertThat(courses.member(1L, STUDENT_ID).role()).isEqualTo(CourseRole.TA);
    }

    @Test
    void 管理员可以调整任意课程成员角色() {
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);
        courses.givenMember(1L, STUDENT_ID, CourseRole.STUDENT);

        service.assignRole(admin(ADMIN_ID), 1L, STUDENT_ID, CourseRole.TA);

        assertThat(courses.member(1L, STUDENT_ID).role()).isEqualTo(CourseRole.TA);
    }

    @Test
    void 既不是课程教师也不是管理员时抛ID3003() {
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);
        courses.givenMember(1L, STUDENT_ID, CourseRole.STUDENT);

        assertThatThrownBy(() -> service.assignRole(teacher(OTHER_TEACHER_ID), 1L, STUDENT_ID, CourseRole.TA))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("ID-3003");
        assertThat(courses.member(1L, STUDENT_ID).role()).isEqualTo(CourseRole.STUDENT);
    }

    @Test
    void 目标用户不是课程成员时抛ID4003() {
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);

        assertThatThrownBy(() -> service.assignRole(teacher(TEACHER_ID), 1L, STUDENT_ID, CourseRole.TA))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("ID-4003");
    }

    @Test
    void 不能把课程教师降级为其他角色() {
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);
        courses.givenMember(1L, TEACHER_ID, CourseRole.TEACHER);

        assertThatThrownBy(() -> service.assignRole(teacher(TEACHER_ID), 1L, TEACHER_ID, CourseRole.TA))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1013");
    }

    @Test
    void 课程列表带教师名与我的课程角色() {
        users.given(TEACHER_ID, "100", "张老师", "zhang@example.com",
                com.campusoverflow.shared.security.Role.TEACHER, true);
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);
        courses.given(2L, "CS102", "操作系统", "2026秋", OTHER_TEACHER_ID);
        courses.givenMember(1L, STUDENT_ID, CourseRole.STUDENT);

        var views = service.listAll(student(STUDENT_ID));

        assertThat(views).hasSize(2);
        assertThat(views.get(0).teacherName()).isEqualTo("张老师");
        assertThat(views.get(0).myRole()).isEqualTo("STUDENT");
        assertThat(views.get(1).myRole()).isNull();
        assertThat(views.get(1).teacherName()).isEqualTo("—");
    }

    @Test
    void 未登录也能看课程列表但没有我的角色() {
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);

        var views = service.listAll(null);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).myRole()).isNull();
    }

    @Test
    void 我的课程只包含已加入的课程() {
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);
        courses.given(2L, "CS102", "操作系统", "2026秋", OTHER_TEACHER_ID);
        courses.givenMember(2L, STUDENT_ID, CourseRole.TA);

        var mine = service.myCourses(STUDENT_ID);

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).code()).isEqualTo("CS102");
        assertThat(mine.get(0).myRole()).isEqualTo("TA");
    }

    @Test
    void 成员列表带昵称且未知用户降级为占位文案() {
        users.given(TEACHER_ID, "100", "张老师", "zhang@example.com",
                com.campusoverflow.shared.security.Role.TEACHER, true);
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);
        courses.givenMember(1L, TEACHER_ID, CourseRole.TEACHER);
        courses.givenMember(1L, 999L, CourseRole.STUDENT);

        var members = service.members(1L);

        assertThat(members).hasSize(2);
        assertThat(members).extracting(CourseMemberView::displayName).containsExactlyInAnyOrder("张老师", "未知用户");
        assertThat(members).extracting(CourseMemberView::role)
                .containsExactlyInAnyOrder(CourseRole.TEACHER, CourseRole.STUDENT);
    }

    @Test
    void 查询不存在课程的成员列表抛ID4002() {
        assertThatThrownBy(() -> service.members(404L))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("ID-4002");
    }
}
