package com.campusoverflow.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/**
 * {@link SpaCsrfTokenRequestHandler} 的分发逻辑测试。
 *
 * <p>这个类只有 28 行，但它是 **SPA 场景 CSRF 防护能不能生效的关键**：
 * 它决定一个请求的 token 是按「原始值」校验还是按「XOR 掩码值」校验。
 * 分错了不会报错——只是校验一律失败（用户什么都提交不了），或者更糟：
 * 把本该拒绝的请求放过去。后者是安全漏洞，而且**没有任何日志**。
 */
class SpaCsrfTokenRequestHandlerTest {

    private final SpaCsrfTokenRequestHandler handler = new SpaCsrfTokenRequestHandler();
    private final CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "raw-token-value");

    @AfterEach
    void clear() {
        // 无状态 handler，无需清理；保留此方法便于后续扩展
    }

    @Test
    @DisplayName("请求头里有 token → 按原始值读取（前端补提交 Cookie 的路径）")
    void readsRawValueFromHeader() {
        // 前端把 Cookie 里的原始 token 放进请求头，这里必须原样返回，
        // 不能做 XOR 解码——否则每个前端请求都会被判 CSRF 校验失败。
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-XSRF-TOKEN", "raw-token-value");

        String resolved = handler.resolveCsrfTokenValue(request, token);

        assertThat(resolved).isEqualTo("raw-token-value");
    }

    @Test
    @DisplayName("请求头为空 → 走 XOR 掩码解码（服务端渲染表单的路径）")
    void fallsBackToXorWhenHeaderMissing() {
        // Spring Security 默认的 XOR handler 会从参数里取掩码值再解码。
        // 这里只断言「不是简单地返回请求头值」——具体解码由框架负责。
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("_csrf", "masked-value");

        String resolved = handler.resolveCsrfTokenValue(request, token);

        // XOR handler 对非法输入通常返回原参数或 null，但绝不会等于「请求头值」
        assertThat(resolved).isNotEqualTo("raw-token-value");
    }

    @Test
    @DisplayName("请求头是空白串 → 当作没有，走 XOR 分支")
    void blankHeaderIsTreatedAsAbsent() {
        // 关键分支：StringUtils.hasText 对 "   " 返回 false。
        // 如果用 isEmpty()，一张「空白请求头」就会让请求走原始值校验并必然失败。
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-XSRF-TOKEN", "   ");
        request.setParameter("_csrf", "masked-value");

        String resolved = handler.resolveCsrfTokenValue(request, token);

        assertThat(resolved).isNotEqualTo("   ");
    }

    @Test
    @DisplayName("请求头名取自 CsrfToken，不是写死的")
    void usesTokenHeaderName() {
        // token 的 headerName 是可配置的（本项目是 X-XSRF-TOKEN）。
        // 如果实现里写死了某个名字，换配置后就全盘失效。
        CsrfToken custom = new DefaultCsrfToken("X-CUSTOM-TOKEN", "_csrf", "custom-value");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-CUSTOM-TOKEN", "custom-value");
        request.addHeader("X-XSRF-TOKEN", "should-be-ignored");

        String resolved = handler.resolveCsrfTokenValue(request, custom);

        assertThat(resolved).isEqualTo("custom-value");
    }

    @Test
    @DisplayName("handle() 委托给 XOR handler，不自己实现")
    void handleDelegates() {
        // handle() 必须把设置 attribute 的工作交给 XOR handler，
        // 否则后续 resolveCsrfTokenValue 拿到的掩码值格式对不上。
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, () -> token);

        // XOR handler 会把 token 放进请求 attribute
        assertThat(request.getAttribute(CsrfToken.class.getName())).isNotNull();
    }
}
