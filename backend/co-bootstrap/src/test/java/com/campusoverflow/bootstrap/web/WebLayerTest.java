package com.campusoverflow.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.ConflictException;
import com.campusoverflow.shared.domain.DomainException;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.ActorAware;
import com.campusoverflow.shared.security.Role;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.core.MethodParameter;

/** 接口层装配契约：Problem Details 映射、Actor 参数解析、MVC 注册表。 */
class WebLayerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ActorArgumentResolver resolver = new ActorArgumentResolver();

    @BeforeEach
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mapsDomainExceptionsToTheirSuggestedStatusAndBusinessCode() {
        assertProblem(handler.domain(new BusinessRuleException("QA-1005", "标签超限")), 422, "QA-1005");
        assertProblem(handler.domain(new ConflictException("QA-3001", "重复采纳")), 409, "QA-3001");
        assertProblem(handler.domain(new ForbiddenException("QA-2001", "无权编辑")), 403, "QA-2001");
        assertProblem(handler.domain(new NotFoundException("QA-4001", "问题不存在")), 404, "QA-4001");
    }

    @Test
    void mapsSecurityAndInfrastructureExceptionsToStableCodes() {
        assertProblem(handler.locked(new LockedException("账号锁定")), 429, "AUTH-429");
        assertProblem(handler.unauthenticated(new BadCredentialsException("凭据无效")), 401, "AUTH-401");
        assertProblem(handler.forbidden(new AccessDeniedException("denied")), 403, "AUTH-403");
        assertProblem(handler.optimisticLock(mock(ObjectOptimisticLockingFailureException.class)), 409,
                "SYS-409-CONCURRENT");

        DataIntegrityViolationException duplicate = mock(DataIntegrityViolationException.class);
        when(duplicate.getMostSpecificCause()).thenReturn(new IllegalStateException("Duplicate entry"));
        assertProblem(handler.integrity(duplicate), 409, "SYS-409-DUPLICATE");

        assertProblem(handler.badArgument(new IllegalArgumentException("bad")), 400, "SYS-400");
        assertProblem(handler.unexpected(new RuntimeException("boom")), 500, "SYS-500");
    }

    @Test
    void validationFailuresExposeFieldLevelErrors() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "form");
        binding.addError(new FieldError("form", "title", "标题不能为空"));
        binding.addError(new FieldError("form", "body", null));
        MethodParameter parameter = new MethodParameter(Handlers.class.getDeclaredMethod("submit", String.class), 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, binding);

        ResponseEntity<Object> response = handler.handleMethodArgumentNotValid(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, mock(WebRequest.class));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail pd = (ProblemDetail) response.getBody();
        assertThat(pd).isNotNull();
        assertThat(pd.getProperties()).containsEntry("code", "SYS-400-VALIDATION");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> errors = (List<Map<String, String>>) pd.getProperties().get("errors");
        assertThat(errors).containsExactly(
                Map.of("field", "title", "message", "标题不能为空"),
                Map.of("field", "body", "message", "不合法"));
    }

    @Test
    void resolverOnlySupportsActorAndOptionalActor() throws Exception {
        assertThat(resolver.supportsParameter(parameter("required", Actor.class))).isTrue();
        assertThat(resolver.supportsParameter(parameter("optional", Optional.class))).isTrue();
        assertThat(resolver.supportsParameter(parameter("unrelated", String.class))).isFalse();
    }

    @Test
    void resolverExtractsActorFromAuthenticatedPrincipal() throws Exception {
        Actor actor = new Actor(7L, "张三", Role.STUDENT);
        ActorAware principal = () -> actor;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a"));

        assertThat(resolver.resolveArgument(parameter("required", Actor.class), null, null, null))
                .isSameAs(actor);
        assertThat(resolver.resolveArgument(parameter("optional", Optional.class), null, null, null))
                .isEqualTo(Optional.of(actor));
    }

    @Test
    void resolverReturnsEmptyOptionalForAnonymousVisitor() throws Exception {
        assertThat(resolver.resolveArgument(parameter("optional", Optional.class), null, null, null))
                .isEqualTo(Optional.empty());
    }

    @Test
    void resolverRejectsAnonymousVisitorWhenActorIsRequired() throws Exception {
        assertThatThrownBy(() -> resolver.resolveArgument(parameter("required", Actor.class), null, null, null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessage("请先登录");
    }

    @Test
    void webConfigRegistersActorArgumentResolver() {
        List<org.springframework.web.method.support.HandlerMethodArgumentResolver> resolvers =
                new java.util.ArrayList<>();
        new WebConfig().addArgumentResolvers(resolvers);
        assertThat(resolvers).hasSize(1).allMatch(r -> r instanceof ActorArgumentResolver);
    }

    private static MethodParameter parameter(String method, Class<?> type) throws NoSuchMethodException {
        return new MethodParameter(Handlers.class.getDeclaredMethod(method, type), 0);
    }

    private static void assertProblem(ResponseEntity<ProblemDetail> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", code);
        assertThat(response.getBody().getTitle()).isEqualTo(HttpStatus.valueOf(status).getReasonPhrase());
        assertThat(response.getBody().getDetail()).isNotBlank();
    }

    static class Handlers {
        void required(Actor actor) {
        }

        void optional(Optional<Actor> actor) {
        }

        void unrelated(String value) {
        }

        void submit(String title) {
        }
    }
}
