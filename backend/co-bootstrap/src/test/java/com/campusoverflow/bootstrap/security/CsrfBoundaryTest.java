package com.campusoverflow.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/**
 * 补齐 {@link CsrfComponentsTest}（#77）遗漏的三条边界。
 *
 * <p>已有用例覆盖了主路径，但三处关键点没有覆盖——这三处恰好都是
 * 「实现改一个字就失效，而现有测试全绿」的类型：
 *
 * <ol>
 *   <li>空白请求头必须当作「没有」（实现用 {@code hasText} 而非 {@code isEmpty}）；</li>
 *   <li>请求头名必须取自 {@link CsrfToken}，不能写死；</li>
 *   <li>{@link CsrfCookieFilter} 必须真的调用 {@code getToken()}——
 *       只断言「不抛异常」的话，一个空实现同样能通过。</li>
 * </ol>
 */
class CsrfBoundaryTest {

    // ---------- SpaCsrfTokenRequestHandler ----------

    @Test
    @DisplayName("空白请求头当作没有，走 XOR 委托而不是原始值")
    void blankHeaderIsTreatedAsAbsent() {
        // 实现是 StringUtils.hasText(request.getHeader(...))：
        //   "".isBlank()   → true  → hasText = false  → 走委托
        //   "   ".isBlank() → true  → hasText = false  → 走委托
        // 若有人「优化」成 !header.isEmpty()，"   " 会被当成有值，
        // 于是返回 "   " 参与校验，必然与真实 token 不符——所有请求 403 且无日志。
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "raw-token");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-XSRF-TOKEN", "   ");

        String resolved = new SpaCsrfTokenRequestHandler().resolveCsrfTokenValue(request, token);

        // XOR 委托在既无有效头又无参数时返回 null（与 #77 的断言语义一致）
        assertThat(resolved).isNull();
    }

    @Test
    @DisplayName("请求头名取自 CsrfToken，不是写死的常量")
    void headerNameComesFromToken() {
        // #77 的用例里，传进去的 token headerName 恰好等于常量 X-XSRF-TOKEN，
        // 所以即使实现写死了常量也能通过——那条用例区分不出这两种实现。
        // 这里换一个 headerName，就能真正验证「读的是 token 自带的那个」。
        CsrfToken custom = new DefaultCsrfToken("X-CUSTOM-TOKEN", "_csrf", "custom-value");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-CUSTOM-TOKEN", "custom-value");
        // 同时放一个默认头，值不同：如果实现读了默认头，结果会是 wrong
        request.addHeader("X-XSRF-TOKEN", "wrong");

        String resolved = new SpaCsrfTokenRequestHandler().resolveCsrfTokenValue(request, custom);

        assertThat(resolved).isEqualTo("custom-value");
    }

    // ---------- CsrfCookieFilter ----------

    @Test
    @DisplayName("CsrfCookieFilter 真的调用了 getToken()，不只是「没抛异常」")
    void filterActuallyInvokesGetToken() throws Exception {
        // 这是本 PR 最有价值的一条断言。
        // #77 的用例只写 assertThatCode(...).doesNotThrowAnyException()，
        // 但一个 isEmpty 的空实现同样不抛异常——而那样 XSRF-TOKEN Cookie
        // 就永远发不出去，前端所有 POST 一律 403。
        //
        // 用 mock 断言副作用确实发生，才能把这个过滤器钉住。
        CsrfToken token = mock(CsrfToken.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CsrfToken.class.getName(), token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new CsrfCookieFilter().doFilter(request, response, chain);

        verify(token).getToken();
        verify(chain).doFilter(request, response);
    }
}
