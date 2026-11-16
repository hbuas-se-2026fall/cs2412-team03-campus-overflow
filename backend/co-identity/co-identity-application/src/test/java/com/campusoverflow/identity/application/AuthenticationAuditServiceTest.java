package com.campusoverflow.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.identity.application.fake.IdentityTestFixtures;
import org.junit.jupiter.api.Test;

/** 认证审计：登录成功/失败都要留痕，且账号一律脱敏（审计日志不是个人信息出口）。 */
class AuthenticationAuditServiceTest {

    private final IdentityTestFixtures.Audit audit = new IdentityTestFixtures.Audit();
    private final AuthenticationAuditService service = new AuthenticationAuditService(audit);

    @Test
    void 登录成功记录操作者与脱敏账号() {
        service.loginSucceeded(200L, "20240001");

        assertThat(audit.records()).hasSize(1);
        assertThat(audit.last()).isEqualTo("LOGIN_SUCCESS|200|USER|200|2024****01");
        assertThat(audit.last()).doesNotContain("20240001");
    }

    @Test
    void 登录失败没有操作者但保留脱敏账号与原因() {
        service.loginFailed("20240002", "密码错误");

        assertThat(audit.records()).hasSize(1);
        assertThat(audit.last()).isEqualTo("LOGIN_FAILURE|null|USER|null|2024****02 密码错误");
        assertThat(audit.last()).doesNotContain("20240002");
    }

    @Test
    void 短账号与空账号按统一规则脱敏为四个星号() {
        service.loginFailed("abc", "账号不存在");
        service.loginSucceeded(1L, null);

        assertThat(audit.records().get(0)).endsWith("**** 账号不存在");
        assertThat(audit.records().get(1)).endsWith("|****");
    }
}
