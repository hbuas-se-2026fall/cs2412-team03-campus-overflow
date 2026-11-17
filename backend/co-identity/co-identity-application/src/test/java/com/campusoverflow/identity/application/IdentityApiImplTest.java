package com.campusoverflow.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.identity.api.CourseSummary;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.identity.application.fake.IdentityTestFixtures;
import com.campusoverflow.identity.domain.CourseRole;
import com.campusoverflow.shared.security.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 跨上下文只读 API：其他上下文（Q&A / Reputation）拿用户与课程信息的唯一入口，
 * 因此这里的边界行为（空集合、缺失用户、课程管理权限）必须钉死。
 */
class IdentityApiImplTest {

    private static final long USER_ID = 200L;
    private static final long TEACHER_ID = 100L;
    private static final long ADMIN_ID = 900L;
    private static final long COURSE_ID = 1L;

    private final IdentityTestFixtures.Users users = new IdentityTestFixtures.Users();
    private final IdentityTestFixtures.Courses courses = new IdentityTestFixtures.Courses();
    private final IdentityApiImpl api = new IdentityApiImpl(users, courses);

    @Test
    void findUser映射为UserSummary() {
        users.given(USER_ID, "20240001", "林小问", "lin@example.com", Role.STUDENT, true);

        UserSummary summary = api.findUser(USER_ID).orElseThrow();

        assertThat(summary.id()).isEqualTo(USER_ID);
        assertThat(summary.displayName()).isEqualTo("林小问");
        assertThat(summary.role()).isEqualTo(Role.STUDENT);
        assertThat(summary.verified()).isTrue();
        assertThat(summary.college()).isEqualTo("计算机工程学院");
    }

    @Test
    void findUser不存在时返回空() {
        assertThat(api.findUser(404L)).isEmpty();
    }

    @Test
    void findUsers对空集合与null都返回空Map() {
        assertThat(api.findUsers(null)).isEmpty();
        assertThat(api.findUsers(List.of())).isEmpty();
    }

    @Test
    void findUsers批量查询并去重忽略不存在的id() {
        users.given(1L, "20240001", "甲", "a@example.com", Role.STUDENT, false);
        users.given(2L, "20240002", "乙", "b@example.com", Role.STUDENT, false);

        Map<Long, UserSummary> found = api.findUsers(List.of(1L, 2L, 2L, 404L));

        assertThat(found).hasSize(2);
        assertThat(found.get(1L).displayName()).isEqualTo("甲");
        assertThat(found.get(2L).displayName()).isEqualTo("乙");
    }

    @Test
    void findByDisplayName用于提及解析() {
        users.given(USER_ID, "20240001", "林小问", "lin@example.com", Role.STUDENT, false);

        assertThat(api.findByDisplayName("林小问")).isPresent();
        assertThat(api.findByDisplayName("不存在的人")).isEmpty();
    }

    @Test
    void findCourse映射为CourseSummary() {
        courses.given(COURSE_ID, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);

        CourseSummary summary = api.findCourse(COURSE_ID).orElseThrow();

        assertThat(summary.id()).isEqualTo(COURSE_ID);
        assertThat(summary.code()).isEqualTo("CS101");
        assertThat(summary.name()).isEqualTo("数据结构与算法");
        assertThat(summary.term()).isEqualTo("2026秋");
        assertThat(summary.teacherId()).isEqualTo(TEACHER_ID);
        assertThat(api.findCourse(404L)).isEmpty();
    }

    @Test
    void 不存在的用户对任何课程都没有管理权() {
        assertThat(api.canManageCourse(404L, COURSE_ID)).isFalse();
        assertThat(api.canManageCourse(404L, null)).isFalse();
    }

    @Test
    void 管理员对任意课程都有管理权() {
        users.given(ADMIN_ID, "A0001", "管理员", "admin@example.com", Role.ADMIN, true);

        assertThat(api.canManageCourse(ADMIN_ID, COURSE_ID)).isTrue();
        assertThat(api.canManageCourse(ADMIN_ID, null)).isTrue();
    }

    @Test
    void 教师在没有课程上下文时可管理有课程上下文时看课程内角色() {
        users.given(TEACHER_ID, "T1001", "张老师", "zhang@example.com", Role.TEACHER, true);

        assertThat(api.canManageCourse(TEACHER_ID, null)).isTrue();
        assertThat(api.canManageCourse(TEACHER_ID, COURSE_ID)).isFalse();

        courses.givenMember(COURSE_ID, TEACHER_ID, CourseRole.TEACHER);
        assertThat(api.canManageCourse(TEACHER_ID, COURSE_ID)).isTrue();
    }

    @Test
    void 学生只有在该课程内是助教或教师时才有管理权() {
        users.given(USER_ID, "20240001", "林小问", "lin@example.com", Role.STUDENT, false);

        assertThat(api.canManageCourse(USER_ID, null)).isFalse();
        assertThat(api.canManageCourse(USER_ID, COURSE_ID)).isFalse();

        courses.givenMember(COURSE_ID, USER_ID, CourseRole.STUDENT);
        assertThat(api.canManageCourse(USER_ID, COURSE_ID)).isFalse();

        courses.givenMember(COURSE_ID, USER_ID, CourseRole.TA);
        assertThat(api.canManageCourse(USER_ID, COURSE_ID)).isTrue();
    }
}
