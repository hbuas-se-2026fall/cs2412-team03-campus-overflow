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
 * 用户聚合的管理侧不变量：认证、改角色、禁用、可登录判定。
 * {@code UserTest} 已覆盖注册与「不能改自己角色」，这里补齐禁用、角色空值、
 * 未持久化对象、以及「非管理员一律拒绝」这几条分支——它们共同决定「谁能改谁」。
 */
class UserAdministrationTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private static final Actor ADMIN = new Actor(900L, "管理员", Role.ADMIN);
    private static final Actor STUDENT = new Actor(1L, "林小问", Role.STUDENT);

    private static User student(long id) {
        User user = User.register("20240001", "林小问", "lin@example.com", "hashed", "计算机工程学院",
                "软件工程2024级", NOW);
        if (id > 0) {
            user.assignId(id);
        }
        return user;
    }

    @Test
    void 禁用把状态置为不可登录() {
        User user = student(1L);
        assertThat(user.canLogin()).isTrue();

        user.disable(ADMIN);

        assertThat(user.status()).isEqualTo(UserStatus.DISABLED);
        assertThat(user.canLogin()).isFalse();
    }

    @Test
    void 非管理员不能禁用他人() {
        User user = student(1L);

        assertThatThrownBy(() -> user.disable(STUDENT))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("ID-3001");
        assertThat(user.canLogin()).isTrue();
    }

    @Test
    void 不能禁用自己() {
        User admin = User.createStaff(ADMIN, "A0001", "管理员本人", "admin@example.com", "hashed",
                Role.ADMIN, null, NOW);
        admin.assignId(900L);

        assertThatThrownBy(() -> admin.disable(ADMIN))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1007");
        assertThat(admin.canLogin()).isTrue();
    }

    @Test
    void 未持久化的对象不能被禁用或改角色() {
        User draft = student(0);

        assertThatThrownBy(() -> draft.disable(ADMIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未持久化");
        assertThatThrownBy(() -> draft.changeRole(ADMIN, Role.TA))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 管理员把学生提为助教() {
        User user = student(1L);

        user.changeRole(ADMIN, Role.TA);

        assertThat(user.role()).isEqualTo(Role.TA);
    }

    @Test
    void 角色不能为空() {
        User user = student(1L);

        assertThatThrownBy(() -> user.changeRole(ADMIN, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(user.role()).isEqualTo(Role.STUDENT);
    }

    @Test
    void 非管理员不能改角色() {
        User user = student(1L);

        assertThatThrownBy(() -> user.changeRole(STUDENT, Role.TA))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("ID-3001");
    }

    @Test
    void 创建教职工时非管理员被拒绝() {
        assertThatThrownBy(() -> User.createStaff(STUDENT, "T1", "李老师", "li@example.com", "hashed",
                Role.TEACHER, null, NOW))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).code()).isEqualTo("ID-3001");
    }

    @Test
    void 学院与班级超长会被截断而不是报错() {
        User user = User.register("20240002", "长字段", "long@example.com", "hashed",
                "计算机工程学院".repeat(10), "软件工程2024级".repeat(10), NOW);

        assertThat(user.college()).hasSize(50);
        assertThat(user.className()).hasSize(50);
    }

    @Test
    void 学院与班级留空时存为空值() {
        User user = User.register("20240003", "空字段", "empty@example.com", "hashed", "   ", "", NOW);

        assertThat(user.college()).isNull();
        assertThat(user.className()).isNull();
    }

    @Test
    void 主键只能回填一次() {
        User user = student(1L);

        assertThatThrownBy(() -> user.assignId(2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ID 已分配");
    }

    @Test
    void 重建用户不做业务校验() {
        User restored = User.reconstitute(1L, "20240001", "林小问", "lin@example.com", "hashed",
                Role.TA, UserStatus.DISABLED, true, "计算机工程学院", "软件工程2024级", NOW, 3);

        assertThat(restored.role()).isEqualTo(Role.TA);
        assertThat(restored.verified()).isTrue();
        assertThat(restored.status()).isEqualTo(UserStatus.DISABLED);
        assertThat(restored.canLogin()).isFalse();
        assertThat(restored.version()).isEqualTo(3);
    }
}
