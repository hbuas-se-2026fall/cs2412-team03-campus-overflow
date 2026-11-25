package com.campusoverflow.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.ConflictException;
import com.campusoverflow.shared.domain.DomainException;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.domain.NotFoundException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * {@link GlobalExceptionHandler} 的行为测试。
 *
 * <p>这个类是**所有对外错误的唯一出口**，95 行代码此前没有任何测试。它一旦出错，
 * 后果不是「返回码不好看」，而是：
 * <ul>
 *   <li>把内部异常信息（SQL、类名、栈）泄露给前端——安全漏洞；</li>
 *   <li>把 409 冲突翻译成 500，前端无法判断该重试还是该提示用户；</li>
 *   <li>把 429 限流吞成 401，客户端会去刷新 token 而不是等待，越刷越封。</li>
 * </ul>
 * 这些都属于「看起来能跑，出事才知道」的类型。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ---------- 领域异常：状态码由异常自己建议，处理器只做翻译 ----------

    @Test
    @DisplayName("NotFoundException → 404，错误码与消息原样传递")
    void notFound() {
        ResponseEntity<ProblemDetail> res = handler.domain(new NotFoundException("QA-404", "问题不存在"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().getProperties()).containsEntry("code", "QA-404");
        assertThat(res.getBody().getDetail()).isEqualTo("问题不存在");
    }

    @Test
    @DisplayName("ForbiddenException → 403")
    void forbidden() {
        ResponseEntity<ProblemDetail> res = handler.domain(new ForbiddenException("QA-403", "不是作者"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().getProperties()).containsEntry("code", "QA-403");
    }

    @Test
    @DisplayName("ConflictException → 409")
    void conflict() {
        ResponseEntity<ProblemDetail> res = handler.domain(new ConflictException("REP-409", "已采纳过"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().getProperties()).containsEntry("code", "REP-409");
    }

    @Test
    @DisplayName("BusinessRuleException → 422，不是 400")
    void businessRule() {
        // 422 vs 400 的区别对前端有实际意义：400 表示请求格式错了（不该重试），
        // 422 表示格式没问题但业务规则不允许（用户应该看到原因）。
        ResponseEntity<ProblemDetail> res = handler.domain(
                new BusinessRuleException("QA-422", "标题不能为空"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res.getBody().getProperties()).containsEntry("code", "QA-422");
    }

    @Test
    @DisplayName("每种领域异常的状态码都由自己决定，处理器不硬编码")
    void domainStatusComesFromException() {
        // 用一个自定义状态码的领域异常，证明处理器确实读的是 suggestedStatus()
        // 而不是按类型写死的 if-else。
        DomainException custom = new DomainException("XX-418", "我是一个茶壶") {
            @Override
            public int suggestedStatus() {
                return 418;
            }
        };

        ResponseEntity<ProblemDetail> res = handler.domain(custom);

        assertThat(res.getStatusCode().value()).isEqualTo(418);
        assertThat(res.getBody().getProperties()).containsEntry("code", "XX-418");
    }

    // ---------- 认证相关 ----------

    @Test
    @DisplayName("LockedException → 429 而不是 401——否则客户端会越刷越封")
    void locked() {
        // 关键：账户锁定如果返回 401，客户端通常会「刷新 token 重试」，
        // 而账户锁定的正确语义是「等一会儿」，必须给 429。
        ResponseEntity<ProblemDetail> res = handler.locked(new LockedException("账户已锁定"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res.getBody().getProperties()).containsEntry("code", "AUTH-429");
        assertThat(res.getBody().getDetail()).isEqualTo("账户已锁定");
    }

    @Test
    @DisplayName("AuthenticationException → 401")
    void unauthenticated() {
        ResponseEntity<ProblemDetail> res = handler.unauthenticated(new BadCredentialsException("密码错误"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().getProperties()).containsEntry("code", "AUTH-401");
    }

    @Test
    @DisplayName("AccessDeniedException 的原始消息不外泄")
    void forbiddenDoesNotLeakDetail() {
        // 鉴权失败的原始消息里常带资源路径甚至用户名，不能原样返回给调用方。
        ResponseEntity<ProblemDetail> res = handler.forbidden(
                new AccessDeniedException("User 230593641 lacks ROLE_ADMIN for /api/v1/admin/users"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().getProperties()).containsEntry("code", "AUTH-403");
        assertThat(res.getBody().getDetail())
                .isEqualTo("无权访问该资源")
                .doesNotContain("230593641")
                .doesNotContain("ROLE_ADMIN");
    }

    // ---------- 并发与数据完整性 ----------

    @Test
    @DisplayName("乐观锁冲突 → 409，提示刷新而不是报错")
    void optimisticLock() {
        ResponseEntity<ProblemDetail> res = handler.optimisticLock(
                new ObjectOptimisticLockingFailureException(Object.class, 1L));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().getProperties()).containsEntry("code", "SYS-409-CONCURRENT");
        assertThat(res.getBody().getDetail()).contains("刷新");
    }

    @Test
    @DisplayName("唯一约束冲突 → 409，且不把 SQL 语句返回给前端")
    void integrityDoesNotLeakSql() {
        // DataIntegrityViolationException 的 message 里是完整 SQL 与约束名，
        // 直接返回等于把数据库结构告诉攻击者。
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("Duplicate entry 'x' for key 'qa_question.uk_qa_question_title'"));

        ResponseEntity<ProblemDetail> res = handler.integrity(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().getProperties()).containsEntry("code", "SYS-409-DUPLICATE");
        assertThat(res.getBody().getDetail())
                .doesNotContain("uk_qa_question_title")
                .doesNotContain("Duplicate entry");
    }

    // ---------- 参数与兜底 ----------

    @Test
    @DisplayName("IllegalArgumentException → 400，且不回显参数值")
    void badArgument() {
        ResponseEntity<ProblemDetail> res = handler.badArgument(
                new IllegalArgumentException("Cannot deserialize value of type `long` from String \"abc\""));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getProperties()).containsEntry("code", "SYS-400");
        assertThat(res.getBody().getDetail()).isEqualTo("请求参数不合法");
    }

    @Test
    @DisplayName("未预期异常 → 500，消息不泄露内部细节")
    void unexpectedDoesNotLeak() {
        // 最重要的一条：兜底 handler 绝不能把异常 message 原样返回。
        // 否则 NullPointerException 的行号、SQL 语句、内部类名都会出现在前端。
        ResponseEntity<ProblemDetail> res = handler.unexpected(
                new IllegalStateException("java.sql.SQLException: Connection refused: localhost:3306"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().getProperties()).containsEntry("code", "SYS-500");
        assertThat(res.getBody().getDetail())
                .isEqualTo("服务器开小差了，请稍后重试")
                .doesNotContain("SQLException")
                .doesNotContain("localhost")
                .doesNotContain("3306");
    }

    @Test
    @DisplayName("ProblemDetail 的 title 与 status 一致")
    void titleMatchesStatus() {
        ResponseEntity<ProblemDetail> res = handler.domain(new NotFoundException("QA-404", "不存在"));
        ProblemDetail pd = res.getBody();

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getTitle()).isEqualTo("Not Found");
    }

    // ---------- 参数校验失败 ----------

    @Test
    @DisplayName("Bean Validation 失败 → 400，逐个字段列出原因")
    void validationListsFields() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "form");
        binding.addError(new FieldError("form", "title", "标题不能为空"));
        binding.addError(new FieldError("form", "body", null));
        MethodParameter param = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummy", Object.class), 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(param, binding);

        ResponseEntity<Object> res = handler.handleMethodArgumentNotValid(
                ex, null, HttpStatus.BAD_REQUEST, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail pd = (ProblemDetail) res.getBody();
        assertThat(pd.getProperties()).containsEntry("code", "SYS-400-VALIDATION");
        assertThat(pd.getDetail()).isEqualTo("请求参数校验失败");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> errors = (List<Map<String, String>>) pd.getProperties().get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors).extracting(m -> m.get("field")).containsExactly("title", "body");
        // 没有默认消息的字段要有兜底文案，不能是 null
        assertThat(errors).extracting(m -> m.get("message")).containsExactly("标题不能为空", "不合法");
    }

    @SuppressWarnings("unused")
    private void dummy(Object arg) {
    }
}
