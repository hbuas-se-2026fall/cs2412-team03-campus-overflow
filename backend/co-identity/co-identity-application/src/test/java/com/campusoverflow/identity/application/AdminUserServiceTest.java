package com.campusoverflow.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.identity.api.event.UserRegisteredEvent;
import com.campusoverflow.identity.application.fake.IdentityTestFixtures;
import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.ConflictException;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import org.junit.jupiter.api.Test;

/** 用例：管理员用户管理（认证、创建教职工、调整角色、按认证状态分页）。 */
class AdminUserServiceTest {

    private static final long ADMIN_ID = 900L;
    private static final long USER_ID = 200L;

    private final IdentityTestFixtures.Users users = new IdentityTestFixtures.Users();
    private final IdentityTestFixtures.Events events = new IdentityTestFixtures.Events();
    private final IdentityTestFixtures.Audit audit = new IdentityTestFixtures.Audit();
    private final AdminUserService service = new AdminUserService(users, new IdentityTestFixtures.Hasher(), events,
            audit, IdentityTestFixtures.CLOCK);

    private static Actor admin() {
        return IdentityTestFixtures.admin(ADMIN_ID);
    }

    private static Actor student() {
        return IdentityTestFixtures.student(USER_ID);
    }

    @Test
    void 管理员认证用户成功后落库并记审计() {
        users.givenStudent(USER_ID, "20240001", "林小问", "lin@example.com");

        service.verify(admin(), USER_ID);

        assertThat(users.get(USER_ID).verified()).isTrue();
        assertThat(audit.last()).startsWith("USER_VERIFIED|" + ADMIN_ID + "|USER|" + USER_ID);
    }

    @Test
    void 非管理员认证用户时抛ID3001且不改状态() {
        users.givenStudent(USER_ID, "20240001", "林小问", "lin@example.com");

        assertThatThrownBy(() -> service.verify(student(), USER_ID))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("ID-3001");
        assertThat(users.get(USER_ID).verified()).isFalse();
    }

    @Test
    void 重复认证同一用户抛ID2006() {
        users.given(USER_ID, "20240001", "林小问", "lin@example.com", Role.STUDENT, true);

        assertThatThrownBy(() -> service.verify(admin(), USER_ID))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("ID-2006");
    }

    @Test
    void 认证不存在的用户抛ID4001() {
        assertThatThrownBy(() -> service.verify(admin(), 404L))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code()).isEqualTo("ID-4001");
    }

    @Test
    void 创建教师账号成功且创建即已认证() {
        long id = service.createStaff(admin(), new CreateStaffCommand("T1001", "张老师", "zhang@example.com",
                "abc12345", Role.TEACHER, "计算机工程学院"));

        assertThat(users.get(id).role()).isEqualTo(Role.TEACHER);
        assertThat(users.get(id).verified()).isTrue();
        assertThat(users.get(id).passwordHash()).isEqualTo("hashed:abc12345");
        assertThat(events.of(UserRegisteredEvent.class)).hasSize(1);
        assertThat(events.of(UserRegisteredEvent.class).get(0).role()).isEqualTo(Role.TEACHER);
        assertThat(audit.last()).startsWith("STAFF_CREATED|" + ADMIN_ID + "|USER|" + id);
        assertThat(audit.last()).contains("TEACHER");
    }

    @Test
    void 创建学生账号走注册流程而不是管理员工号创建() {
        assertThatThrownBy(() -> service.createStaff(admin(), new CreateStaffCommand("20240002", "学生号",
                "s@example.com", "abc12345", Role.STUDENT, null)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1005");
    }

    @Test
    void 非管理员创建教职工时抛ID3001() {
        assertThatThrownBy(() -> service.createStaff(student(), new CreateStaffCommand("T1002", "李老师",
                "li@example.com", "abc12345", Role.TEACHER, null)))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("ID-3001");
        assertThat(users.findByUsername("T1002")).isEmpty();
    }

    @Test
    void 工号或昵称或邮箱重复时统一抛ID2001() {
        users.given(1L, "T1001", "张老师", "zhang@example.com", Role.TEACHER, true);

        assertThatThrownBy(() -> service.createStaff(admin(), new CreateStaffCommand("T1001", "新老师",
                "new@example.com", "abc12345", Role.TEACHER, null)))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("ID-2001");

        assertThatThrownBy(() -> service.createStaff(admin(), new CreateStaffCommand("T1002", "张老师",
                "new@example.com", "abc12345", Role.TEACHER, null)))
                .isInstanceOf(ConflictException.class);

        assertThatThrownBy(() -> service.createStaff(admin(), new CreateStaffCommand("T1003", "另一位老师",
                "Zhang@Example.com", "abc12345", Role.TEACHER, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 创建教职工时密码不合规先被拦下() {
        assertThatThrownBy(() -> service.createStaff(admin(), new CreateStaffCommand("T1004", "王老师",
                "wang@example.com", "short", Role.TEACHER, null)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1004");
    }

    @Test
    void 管理员调整他人角色成功并记旧新值() {
        users.givenStudent(USER_ID, "20240001", "林小问", "lin@example.com");

        service.changeRole(admin(), USER_ID, Role.TA);

        assertThat(users.get(USER_ID).role()).isEqualTo(Role.TA);
        assertThat(audit.last()).isEqualTo("ROLE_CHANGED|" + ADMIN_ID + "|USER|" + USER_ID + "|STUDENT -> TA");
    }

    @Test
    void 管理员不能改自己的角色() {
        users.given(ADMIN_ID, "A0001", "管理员", "admin@example.com", Role.ADMIN, true);

        assertThatThrownBy(() -> service.changeRole(admin(), ADMIN_ID, Role.STUDENT))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1006");
        assertThat(users.get(ADMIN_ID).role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void 非管理员调整角色时抛ID3001() {
        users.givenStudent(USER_ID, "20240001", "林小问", "lin@example.com");

        assertThatThrownBy(() -> service.changeRole(student(), USER_ID, Role.TA))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("ID-3001");
    }

    @Test
    void 按认证状态分页查询时账号脱敏() {
        users.given(1L, "20240001", "已认证甲", "a@example.com", Role.STUDENT, true);
        users.given(2L, "20240002", "已认证乙", "b@example.com", Role.STUDENT, true);
        users.given(3L, "20240003", "未认证丙", "c@example.com", Role.STUDENT, false);

        PageResult<UserView> verified = service.listByVerification(admin(), true, new PageRequest(1, 10));
        PageResult<UserView> pending = service.listByVerification(admin(), false, new PageRequest(1, 10));

        assertThat(verified.total()).isEqualTo(2);
        assertThat(verified.items()).extracting(UserView::maskedUsername).containsExactly("2024****01", "2024****02");
        assertThat(pending.total()).isEqualTo(1);
        assertThat(pending.items().get(0).displayName()).isEqualTo("未认证丙");
        assertThat(pending.items().get(0).verified()).isFalse();
    }

    @Test
    void 按认证状态查询时分页参数生效() {
        users.given(1L, "20240001", "甲", "a@example.com", Role.STUDENT, true);
        users.given(2L, "20240002", "乙", "b@example.com", Role.STUDENT, true);
        users.given(3L, "20240003", "丙", "c@example.com", Role.STUDENT, true);

        PageResult<UserView> page2 = service.listByVerification(admin(), true, new PageRequest(2, 2));

        assertThat(page2.total()).isEqualTo(3);
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).displayName()).isEqualTo("丙");
        assertThat(page2.totalPages()).isEqualTo(2);
    }

    @Test
    void 非管理员查看认证列表时抛ID3001() {
        assertThatThrownBy(() -> service.listByVerification(student(), true, new PageRequest(1, 10)))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("ID-3001");
    }
}
