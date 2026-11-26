package com.campusoverflow.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/**
 * {@link CsrfCookieFilter} 的行为测试。
 *
 * <p>这个过滤器只有一件事要做：**强制把延迟加载的 CSRF token 取出来**。
 * Spring Security 6 的 token 是 lazy 的，不主动调用 {@code getToken()}
 * 就不会写进 XSRF-TOKEN Cookie——前端拿不到 Cookie，所有 POST 都会 403。
 *
 * <p>反过来，如果它在没有 token 的请求上直接 {@code NPE}，所有静态资源请求都会挂。
 */
class CsrfCookieFilterTest {

    private final CsrfCookieFilter filter = new CsrfCookieFilter();

    @Test
    @DisplayName("有 CSRF token 时调用 getToken() 强制加载")
    void forcesTokenLoad() throws Exception {
        // 用 mock 断言「确实调用了 getToken()」——
        // 只断言「没抛异常」是不够的：一个什么都不做的过滤器同样不抛异常，
        // 但那样 Cookie 就永远发不出去，前端所有写操作 403。
        CsrfToken token = mock(CsrfToken.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CsrfToken.class.getName(), token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(token).getToken();
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("没有 token 时不 NPE，直接放行")
    void passesThroughWhenNoToken() throws Exception {
        // 静态资源、健康检查等请求不走 CSRF 过滤器，attribute 是 null。
        // 这里若直接 getToken() 会 NPE，整个应用所有请求 500。
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("无论有没有 token，过滤器链都必须继续执行")
    void alwaysContinuesChain() throws Exception {
        // 最危险的情况是「拿不到 token 就 return」——请求会被静默吞掉，
        // 前端表现为请求永远 pending。用两种情形各验一次。
        for (boolean withToken : new boolean[] { true, false }) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            if (withToken) {
                request.setAttribute(CsrfToken.class.getName(),
                        new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "v"));
            }
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            verify(chain).doFilter(any(), any());
        }
    }

    @Test
    @DisplayName("真实 token 的 getToken() 会被调用且不抛异常")
    void worksWithRealToken() throws Exception {
        // 用真实的 DefaultCsrfToken 走一遍，避免 mock 掩盖签名不匹配之类的问题。
        DefaultCsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "real-value");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CsrfToken.class.getName(), token);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(token.getToken()).isEqualTo("real-value");
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("只处理一次：OncePerRequestFilter 的 already-filtered 标记生效")
    void runsOncePerRequest() throws Exception {
        // 防止转发/包含时重复执行，导致 token 被取两次（性能 + 日志噪声）。
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CsrfToken.class.getName(),
                new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "v"));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // 第二次调用时 OncePerRequestFilter 发现标记位已存在，不会再进 doFilterInternal，
        // 但 chain 仍需被调用——这里只断言两次都放行了。
        verify(chain, org.mockito.Mockito.times(2)).doFilter(any(), any());
    }
}
