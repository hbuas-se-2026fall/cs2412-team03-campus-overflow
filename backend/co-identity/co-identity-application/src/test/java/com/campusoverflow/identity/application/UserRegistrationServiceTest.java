package com.campusoverflow.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.identity.api.event.UserRegisteredEvent;
import com.campusoverflow.identity.application.fake.IdentityTestFixtures;
import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.ConflictException;
import com.campusoverflow.shared.security.Role;
import org.junit.jupiter.api.Test;

/** 用例：学生自助注册（co-identity-application 此前 0 测试，本类补上注册链路的全部分支）。 */
class UserRegistrationServiceTest {

    private final IdentityTestFixtures.Users users = new IdentityTestFixtures.Users();
    private final IdentityTestFixtures.Events events = new IdentityTestFixtures.Events();
    private final IdentityTestFixtures.Audit audit = new IdentityTestFixtures.Audit();
    private final UserRegistrationService service = new UserRegistrationService(users,
            new IdentityTestFixtures.Hasher(), events, audit, IdentityTestFixtures.CLOCK);

    private static RegisterUserCommand command(String username, String displayName, String email, String password) {
        return new RegisterUserCommand(username, displayName, email, password, "计算机工程学院", "软件工程2024级");
    }

    @Test
    void 注册成功返回主键并落库为未认证学生() {
        long id = service.register(command("20240001", "林小问", "lin@example.com", "abc12345"));

        assertThat(id).isPositive();
        assertThat(users.get(id)).isNotNull();
        assertThat(users.get(id).username()).isEqualTo("20240001");
        assertThat(users.get(id).displayName()).isEqualTo("林小问");
        assertThat(users.get(id).role()).isEqualTo(Role.STUDENT);
        assertThat(users.get(id).verified()).isFalse();
        assertThat(users.get(id).createdAt()).isEqualTo(IdentityTestFixtures.NOW);
    }

    @Test
    void 落库的是密码哈希而不是明文() {
        long id = service.register(command("20240002", "易子恒", "yi@example.com", "abc12345"));

        assertThat(users.get(id).passwordHash()).isEqualTo("hashed:abc12345");
        assertThat(users.get(id).passwordHash()).isNotEqualTo("abc12345");
    }

    @Test
    void 注册成功发布一条UserRegisteredEvent() {
        long id = service.register(command("20240003", "肖蕴晨", "xiao@example.com", "abc12345"));

        assertThat(events.of(UserRegisteredEvent.class)).hasSize(1);
        UserRegisteredEvent event = events.of(UserRegisteredEvent.class).get(0);
        assertThat(event.userId()).isEqualTo(id);
        assertThat(event.displayName()).isEqualTo("肖蕴晨");
        assertThat(event.role()).isEqualTo(Role.STUDENT);
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.occurredAt()).isEqualTo(IdentityTestFixtures.NOW);
    }

    @Test
    void 注册成功记一条USER_REGISTERED审计且账号脱敏() {
        service.register(command("20240004", "黄振亚", "huang@example.com", "abc12345"));

        assertThat(audit.records()).hasSize(1);
        assertThat(audit.last()).startsWith("USER_REGISTERED|").contains("USER").contains("2024****04");
        assertThat(audit.last()).doesNotContain("20240004");
    }

    @Test
    void 密码长度不足时拒绝且不落库不发事件() {
        assertThatThrownBy(() -> service.register(command("20240005", "孔志敏", "kong@example.com", "abc123")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("8–64");

        assertThat(users.findByUsername("20240005")).isEmpty();
        assertThat(events.published()).isEmpty();
        assertThat(audit.records()).isEmpty();
    }

    @Test
    void 密码缺少数字时拒绝() {
        assertThatThrownBy(() -> service.register(command("20240006", "鲍工", "bao@example.com", "abcdefgh")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("字母和数字");
    }

    @Test
    void 学号已注册时抛ID2001() {
        users.givenStudent(1L, "20240007", "已存在", "exist@example.com");

        assertThatThrownBy(() -> service.register(command("20240007", "新昵称", "new@example.com", "abc12345")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("该学号/工号已注册")
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("ID-2001");
    }

    @Test
    void 昵称已被占用时抛ID2002() {
        users.givenStudent(1L, "20240008", "重名", "a@example.com");

        assertThatThrownBy(() -> service.register(command("20240009", "重名", "b@example.com", "abc12345")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("该昵称已被使用")
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("ID-2002");
    }

    @Test
    void 邮箱已被占用时抛ID2003且忽略大小写() {
        users.givenStudent(1L, "20240010", "占用邮箱", "Taken@Example.com");

        assertThatThrownBy(() -> service.register(command("20240011", "另一个人", "taken@example.com", "abc12345")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("该邮箱已被使用")
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("ID-2003");
    }

    @Test
    void 学号格式非法时在聚合内被拦下() {
        assertThatThrownBy(() -> service.register(command("20", "格式非法", "c@example.com", "abc12345")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1001");
    }

    @Test
    void 昵称含空格时被拦下() {
        assertThatThrownBy(() -> service.register(command("20240012", "林 小问", "d@example.com", "abc12345")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1002");
    }

    @Test
    void 邮箱格式非法时被拦下且统一小写存库() {
        assertThatThrownBy(() -> service.register(command("20240013", "邮箱非法", "not-an-email", "abc12345")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("ID-1003");

        long id = service.register(command("20240014", "邮箱正常", "Upper@Example.COM", "abc12345"));
        assertThat(users.get(id).email()).isEqualTo("upper@example.com");
    }
}
