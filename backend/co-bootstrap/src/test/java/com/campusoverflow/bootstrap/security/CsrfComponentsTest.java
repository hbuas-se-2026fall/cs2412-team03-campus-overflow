package com.campusoverflow.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/** SPA 双提交 Cookie 的两个组件：强制下发 token、按请求来源选择原始值 / XOR 掩码值校验。 */
class CsrfComponentsTest {

    private static final String HEADER = "X-XSRF-TOKEN";

    @Test
    void csrfCookieFilterForcesDeferredTokenToBeLoaded() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        CsrfToken token = mock(CsrfToken.class);
        when(token.getToken()).thenReturn("raw-token");
        request.setAttribute(CsrfToken.class.getName(), token);

        new CsrfCookieFilter().doFilter(request, response, chain);

        verify(token).getToken();
        verify(chain).doFilter(request, response);
    }

    @Test
    void csrfCookieFilterPassesThroughWhenNoTokenIsPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        assertThatCode(() -> new CsrfCookieFilter().doFilter(request, response, chain))
                .doesNotThrowAnyException();
        verify(chain).doFilter(request, response);
    }

    @Test
    void spaHandlerUsesRawHeaderValueWhenFrontendSendsIt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "raw-token");
        CsrfToken token = new DefaultCsrfToken(HEADER, "_csrf", "raw-token");

        assertThat(new SpaCsrfTokenRequestHandler().resolveCsrfTokenValue(request, token))
                .isEqualTo("raw-token");
    }

    @Test
    void spaHandlerFallsBackToXorDelegateWithoutHeader() {
        CsrfToken token = new DefaultCsrfToken(HEADER, "_csrf", "raw-token");

        // 既无请求头也无表单参数时，XOR 委托按 Spring Security 语义返回 null。
        assertThat(new SpaCsrfTokenRequestHandler()
                .resolveCsrfTokenValue(new MockHttpServletRequest(), token)).isNull();
    }

    @Test
    void spaHandlerPublishesTokenAsRequestAttribute() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        CsrfToken token = new DefaultCsrfToken(HEADER, "_csrf", "raw-token");

        new SpaCsrfTokenRequestHandler().handle(request, new MockHttpServletResponse(), () -> token);

        assertThat(request.getAttribute(CsrfToken.class.getName())).isNotNull();
    }
}
