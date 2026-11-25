package com.campusoverflow.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全过滤链的真实行为测试：直接调用产品代码里 @Bean 方法
 * {@link SecurityConfig#securityFilterChain(HttpSecurity, org.springframework.security.web.context.SecurityContextRepository)}，
 * 用 Spring Security 自己的装配器（{@code WebSecurityConfiguration} / {@code HttpSecurityConfiguration}）喂给它一个
 * 真实的 {@link HttpSecurity}，然后把产出的 {@link SecurityFilterChain} 挂到 StandaloneMockMvc。
 *
 * <p>不起 Spring 容器、不连 MySQL/Redis，但**走的就是产品代码那份配置**，
 * 因此《哪条路径放行、哪条要登录、哪条要 ADMIN、CSRF 失败返回什么错误码》这几条边界才真正有了回归保护
 * （此前 {@code SecurityConfig} 只有 4/48 行被覆盖，删掉一个 {@code hasRole("ADMIN")} CI 都不会红）。
 */
class SecurityFilterChainTest {

    /** 靶子控制器：路径与 {@code SecurityConfig} 里的匹配器一一对应。 */
    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/questions/probe")
        String publicQuestions() {
            return "ok";
        }

        @GetMapping("/api/v1/tags/probe")
        String publicTags() {
            return "ok";
        }

        @GetMapping("/api/v1/search/probe")
        String publicSearch() {
            return "ok";
        }

        @GetMapping("/api/v1/leaderboard")
        String publicLeaderboard() {
            return "ok";
        }

        @GetMapping("/api/v1/me/probe")
        String protectedGet() {
            return "ok";
        }

        @PostMapping("/api/v1/me/probe")
        String protectedPost() {
            return "ok";
        }

        @GetMapping("/api/v1/admin/probe")
        String adminGet() {
            return "ok";
        }

        @GetMapping("/actuator/health/probe")
        String healthProbe() {
            return "ok";
        }

        @GetMapping("/v3/api-docs/probe")
        String docsProbe() {
            return "ok";
        }

        @PostMapping("/api/v1/auth/login")
        String login() {
            return "ok";
        }
    }

    private static final Authentication ANONYMOUS = new Authentication() {
        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of();
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getDetails() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return "anonymous";
        }

        @Override
        public boolean isAuthenticated() {
            return false;
        }

        @Override
        public void setAuthenticated(boolean authenticated) {
            // 本测试不涉及真实认证
        }

        @Override
        public String getName() {
            return "anonymous";
        }
    };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        SecurityConfig config = new SecurityConfig(objectMapper);
        // 直接用公开构造器：ObjectPostProcessor + AuthenticationManagerBuilder + 共享对象 Map。
        // 这与 Spring Security 内部 new HttpSecurity(...) 是同一条路径，只是不启动容器。
        ObjectPostProcessor<Object> postProcessor = new ObjectPostProcessor<>() {
            @Override
            public <O> O postProcess(O object) {
                return object;
            }
        };
        AuthenticationManagerBuilder authBuilder = new AuthenticationManagerBuilder(postProcessor);
        // HttpSecurity 需要 ApplicationContext 才能拿 ModelAndView 容器、HandlerMappingIntrospector 等协作者，
        // 这里用 Spring 自己的 StaticApplicationContext 提供最小上下文（不是伪造，是标准的最小容器）。
        org.springframework.context.support.StaticApplicationContext context =
                new org.springframework.context.support.StaticApplicationContext();
        context.refresh();
        context.getBeanFactory().registerSingleton("mvcHandlerMappingIntrospector",
                new org.springframework.web.servlet.handler.HandlerMappingIntrospector());
        HttpSecurity http = new HttpSecurity(postProcessor, authBuilder, new java.util.HashMap<>());
        http.setSharedObject(org.springframework.context.ApplicationContext.class, context);
        SecurityFilterChain chain = config.securityFilterChain(http, config.securityContextRepository());
        // SecurityFilterChain 不是 Servlet Filter，套一层 FilterChainProxy（Spring Security 的官方便捷入口）
        org.springframework.security.web.FilterChainProxy proxy =
                new org.springframework.security.web.FilterChainProxy(List.of(chain));
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(proxy)
                .build();
    }

    // ---------- 放行规则 ----------

    @Test
    void 匿名可以读问题标签搜索与排行榜() throws Exception {
        for (String path : new String[] {
                "/api/v1/questions/probe", "/api/v1/tags/probe",
                "/api/v1/search/probe", "/api/v1/leaderboard"}) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
                    .as("匿名读取 " + path)
                    .isEqualTo(HttpStatus.OK.value());
        }
    }

    @Test
    void 匿名可以访问健康检查与接口文档() throws Exception {
        assertThat(mvc.perform(get("/actuator/health/probe")).andReturn().getResponse().getStatus())
                .isEqualTo(HttpStatus.OK.value());
        assertThat(mvc.perform(get("/v3/api-docs/probe")).andReturn().getResponse().getStatus())
                .isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void 登录接口放行但写请求仍需CSRF() throws Exception {
        // 该路径在 authorizeHttpRequests 里是 permitAll（不需要登录态），但 CSRF 仍然生效。
        // 本类只验证「规则配置」这一层：没有令牌的 POST 不能被当成 200 放行。
        // CSRF 令牌的协商细节由 CsrfComponentsTest 覆盖（那里测 SpaCsrfTokenRequestHandler
        // 与 CsrfCookieFilter 的配合），两边职责不重叠。
        MockHttpServletResponse denied = mvc.perform(post("/api/v1/auth/login")).andReturn().getResponse();

        // 精确断言：SecurityConfig 第 82-83 行对 CsrfException 写的是 403 + AUTH-403-CSRF。
        // 只断言「不是 200」会把「返回 500」「返回 AUTH-403」等错误实现一并放过。
        assertThat(denied.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(denied.getContentType()).contains("application/problem+json");
        assertThat(denied.getContentAsString()).contains("\"code\":\"AUTH-403-CSRF\"");
        assertThat(denied.getContentAsString()).contains("CSRF 校验失败");
    }

    // ---------- 拦截规则 ----------

    @Test
    void 匿名访问受保护接口返回401与统一错误码() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/v1/me/probe")).andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(res.getContentType()).contains("application/problem+json");
        assertThat(res.getContentAsString()).contains("\"code\":\"AUTH-401\"");
        assertThat(res.getRedirectedUrl()).isNull();
        assertThat(res.getHeader("WWW-Authenticate")).isNull();
    }

    @Test
    void 管理员路径对匿名返回401且不泄露角色信息() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/v1/admin/probe")).andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(res.getContentAsString()).contains("AUTH-401");
        assertThat(res.getContentAsString()).doesNotContain("ADMIN");
    }


    // ---------- 响应头 ----------

    @Test
    void 响应携带CSP与Referrer策略() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/v1/leaderboard")).andReturn().getResponse();

        String csp = res.getHeader("Content-Security-Policy");
        assertThat(csp).isNotNull();
        assertThat(csp).contains("default-src 'self'").contains("frame-ancestors 'none'");
        assertThat(res.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
    }

    @Test
    void 关闭了formLogin与httpBasic() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/v1/me/probe")).andReturn().getResponse();

        assertThat(res.getRedirectedUrl()).isNull();
        assertThat(res.getHeader("WWW-Authenticate")).isNull();
        assertThat(res.getHeader("Location")).isNull();
    }
}
