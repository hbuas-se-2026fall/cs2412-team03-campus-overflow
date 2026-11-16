package com.campusoverflow.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 课程聚合与成员关系：代码/名称/学期的校验边界，以及课程内角色能否被降级。
 * {@code CourseTest} 只覆盖了「教师建课」与「不能降级教师」，其余校验分支没测。
 */
class CourseDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private static final Actor TEACHER = new Actor(9L, "王老师", Role.TEACHER);
    private static final Actor STUDENT = new Actor(1L, "林小问", Role.STUDENT);

    @Test
    void 课程代码统一大写存储() {
        Course course = Course.create(TEACHER, "cs101", "数据结构与算法", "2026秋", NOW);

        assertThat(course.code()).isEqualTo("CS101");
        assertThat(course.teacherId()).isEqualTo(TEACHER.userId());
        assertThat(course.createdAt()).isEqualTo(NOW);
        assertThat(course.id()).isNull();
    }

    @Test
    void 学生不能建课() {
        assertThatThrownBy(() -> Course.create(STUDENT, "CS101", "数据结构与算法", "2026秋", NOW))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("ID-3002");
    }

    @Test
    void 课程代码格式校验() {
        assertThatThrownBy(() -> Course.create(TEACHER, "C", "数据结构与算法", "2026秋", NOW))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1010");
        assertThatThrownBy(() -> Course.create(TEACHER, "CS 101!", "数据结构与算法", "2026秋", NOW))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1010");
    }

    @Test
    void 课程名称与学期长度校验() {
        assertThatThrownBy(() -> Course.create(TEACHER, "CS101", "课", "2026秋", NOW))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1011");
        assertThatThrownBy(() -> Course.create(TEACHER, "CS101", "数据结构与算法", "秋", NOW))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1012");
        assertThatThrownBy(() -> Course.create(TEACHER, "CS101", null, "2026秋", NOW))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void 主键只能回填一次() {
        Course course = Course.create(TEACHER, "CS101", "数据结构与算法", "2026秋", NOW);

        course.assignId(1L);
        assertThat(course.id()).isEqualTo(1L);
        assertThatThrownBy(() -> course.assignId(2L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 学生加入课程后角色为学生() {
        CourseMember member = CourseMember.student(1L, 1L, NOW);

        assertThat(member.courseId()).isEqualTo(1L);
        assertThat(member.userId()).isEqualTo(1L);
        assertThat(member.role()).isEqualTo(CourseRole.STUDENT);
        assertThat(member.joinedAt()).isEqualTo(NOW);
        assertThat(member.role().canManage()).isFalse();
    }

    @Test
    void 任命助教后拥有管理权且可以改回学生() {
        CourseMember member = CourseMember.student(1L, 1L, NOW);
        member.changeRole(CourseRole.TA);

        assertThat(member.role()).isEqualTo(CourseRole.TA);
        assertThat(member.role().canManage()).isTrue();

        member.changeRole(CourseRole.STUDENT);
        assertThat(member.role().canManage()).isFalse();
    }

    @Test
    void 课程教师的角色不能被降级() {
        CourseMember teacher = CourseMember.teacher(1L, TEACHER.userId(), NOW);

        assertThatThrownBy(() -> teacher.changeRole(CourseRole.STUDENT))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1013");
        assertThat(teacher.role()).isEqualTo(CourseRole.TEACHER);
    }

    @Test
    void 课程教师重复设置教师角色是允许的() {
        CourseMember teacher = CourseMember.teacher(1L, TEACHER.userId(), NOW);

        teacher.changeRole(CourseRole.TEACHER);

        assertThat(teacher.role()).isEqualTo(CourseRole.TEACHER);
    }

    @Test
    void 角色不能改成空值() {
        CourseMember member = CourseMember.student(1L, 1L, NOW);

        assertThatThrownBy(() -> member.changeRole(null)).isInstanceOf(NullPointerException.class);
        assertThat(member.role()).isEqualTo(CourseRole.STUDENT);
    }
}
