package com.campusoverflow.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.identity.application.fake.IdentityTestFixtures;
import com.campusoverflow.identity.domain.CourseRole;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.security.Role;
import org.junit.jupiter.api.Test;

/** 用例：当前登录者信息（me）——把用户资料与「我的课程」拼成一次返回。 */
class UserQueryServiceTest {

    private static final long USER_ID = 200L;
    private static final long TEACHER_ID = 100L;

    private final IdentityTestFixtures.Users users = new IdentityTestFixtures.Users();
    private final IdentityTestFixtures.Courses courses = new IdentityTestFixtures.Courses();
    private final IdentityTestFixtures.Audit audit = new IdentityTestFixtures.Audit();
    private final UserQueryService service = new UserQueryService(users,
            new CourseService(courses, users, IdentityTestFixtures.CLOCK));

    @Test
    void me返回完整资料且账号不脱敏() {
        users.given(USER_ID, "20240001", "林小问", "lin@example.com", Role.STUDENT, false);

        MeView me = service.me(USER_ID);

        assertThat(me.id()).isEqualTo(USER_ID);
        assertThat(me.username()).isEqualTo("20240001");
        assertThat(me.email()).isEqualTo("lin@example.com");
        assertThat(me.displayName()).isEqualTo("林小问");
        assertThat(me.role()).isEqualTo(Role.STUDENT);
        assertThat(me.verified()).isFalse();
        assertThat(me.college()).isEqualTo("计算机工程学院");
        assertThat(me.className()).isEqualTo("软件工程2024级");
        assertThat(me.courses()).isEmpty();
    }

    @Test
    void me带上已加入课程与课程内角色() {
        users.given(USER_ID, "20240001", "林小问", "lin@example.com", Role.STUDENT, false);
        users.given(TEACHER_ID, "T1001", "张老师", "zhang@example.com", Role.TEACHER, true);
        courses.given(1L, "CS101", "数据结构与算法", "2026秋", TEACHER_ID);
        courses.given(2L, "CS102", "操作系统", "2026秋", TEACHER_ID);
        courses.givenMember(1L, USER_ID, CourseRole.TA);

        MeView me = service.me(USER_ID);

        assertThat(me.courses()).hasSize(1);
        assertThat(me.courses().get(0).code()).isEqualTo("CS101");
        assertThat(me.courses().get(0).myRole()).isEqualTo("TA");
        assertThat(me.courses().get(0).teacherName()).isEqualTo("张老师");
    }

    @Test
    void 用户不存在时抛ID4001() {
        assertThatThrownBy(() -> service.me(404L))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("ID-4001");
        assertThat(audit.records()).isEmpty();
    }
}
