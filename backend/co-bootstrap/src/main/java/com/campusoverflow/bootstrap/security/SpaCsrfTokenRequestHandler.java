package com.campusoverflow.bootstrap.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * SPA 场景的 CSRF 处理器。
 *
 * <p>与 Spring 官方示例的唯一差别：<b>handle 不做 XOR 掩码</b>。
 * 官方用 Xor 委托是为了给服务端渲染的表单参数做 BREACH 防护，但本项目只出 JSON API、
 * 没有任何表单，而掩码会让 {@code GET /api/v1/auth/csrf} 的响应体 token 与
 * {@code XSRF-TOKEN} Cookie 的原始值不一致——请求头路径按原始值比对，于是任何
 * "把响应体 token 放进 X-XSRF-TOKEN" 的客户端（REST Client / Postman / 冒烟脚本）恒定 403。
 *
 * <p>响应头路径仍然只认原始值：Cookie 里是什么，请求头就填什么。
 * 空白请求头继续走 XOR 委托（返回 null），与 {@code CsrfBoundaryTest} 的语义一致。
 */
final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    private final CsrfTokenRequestHandler delegate = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        // 关键一行：交给父类，request attribute 上放未掩码的原始 token。
        // 原写法 this.delegate.handle(...) 放的是掩码值，与 Cookie 里的原始值不等。
        super.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        if (StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))) {
            return super.resolveCsrfTokenValue(request, csrfToken); // 原始值
        }
        return this.delegate.resolveCsrfTokenValue(request, csrfToken); // 空白头 / 参数兜底 → null
    }
}
