package com.campusoverflow.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.ActorAware;
import com.campusoverflow.shared.security.Role;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link ActorArgumentResolver} 的白盒测试。
 *
 * <p>这个类只有 48 行，但它是「领域层不依赖 Spring Security」这道架构约束的唯一落点：
 * 一旦它把匿名请求当成已登录，或者把 {@code Optional<Actor>} 解析成 null，
 * 控制器拿到的就是错误的身份——而这类错误在集成测试里很难被发现，因为请求本身是 200。
 *
 * <p>覆盖三个此前从未被测的分支：{@code ParameterizedType} 判定、principal 非
 * {@code ActorAware}、以及三种身份来源（有认证 / 无认证 / principal 不可转换）。
 */
class ActorArgumentResolverTest {

    private final ActorArgumentResolver resolver = new ActorArgumentResolver();

    /** 承载测试用的方法签名，用来取真实的 {@link MethodParameter}。 */
    @SuppressWarnings("unused")
    private void signatures(Actor required, Optional<Actor> optional,
                            Optional<String> wrongOptional, Optional<?> wildcard, String other) {
    }

    private MethodParameter param(String name) {
        for (Method m : getClass().getDeclaredMethods()) {
            if (!m.getName().equals("signatures")) {
                continue;
            }
            for (int i = 0; i < m.getParameterCount(); i++) {
                if (m.getParameters()[i].getName().equals(name)) {
                    return new MethodParameter(m, i);
                }
            }
        }
        throw new AssertionError("找不到参数: " + name);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---------- supportsParameter ----------

    @Test
    @DisplayName("Actor 参数被支持")
    void supportsActor() {
        assertThat(resolver.supportsParameter(param("required"))).isTrue();
    }

    @Test
    @DisplayName("Optional<Actor> 参数被支持")
    void supportsOptionalActor() {
        assertThat(resolver.supportsParameter(param("optional"))).isTrue();
    }

    @Test
    @DisplayName("Optional<String> 不被支持——泛型参数必须精确是 Actor")
    void rejectsOptionalOfOtherType() {
        // 覆盖 L30 的 pt.getActualTypeArguments()[0] == Actor.class 为假的分支
        assertThat(resolver.supportsParameter(param("wrongOptional"))).isFalse();
    }

    @Test
    @DisplayName("Optional<?> 不被支持——通配符不是 ParameterizedType 里的 Actor")
    void rejectsWildcardOptional() {
        assertThat(resolver.supportsParameter(param("wildcard"))).isFalse();
    }

    @Test
    @DisplayName("裸 Optional 不被支持——没有 ParameterizedType 可检查")
    void rejectsRawOptional() {
        // 方法参数写 Optional（不带泛型）时 getGenericParameterType 不是 ParameterizedType，
        // 覆盖 L30 里 type instanceof ParameterizedType 为假的分支。
        MethodParameter raw = null;
        for (Method m : getClass().getDeclaredMethods()) {
            if (m.getName().equals("rawSignature")) {
                raw = new MethodParameter(m, 0);
            }
        }
        assertThat(raw).as("rawSignature 必须存在").isNotNull();
        assertThat(resolver.supportsParameter(raw)).isFalse();
    }

    @SuppressWarnings("unused")
    private void rawSignature(@SuppressWarnings("rawtypes") Optional o) {
    }

    @Test
    @DisplayName("非 Actor 类型参数不被支持")
    void rejectsOtherType() {
        assertThat(resolver.supportsParameter(param("other"))).isFalse();
    }

    // ---------- resolveArgument ----------

    @Test
    @DisplayName("已登录时解析出真实 Actor")
    void resolvesAuthenticatedActor() {
        Actor expected = new Actor(1001L, "刘浩然", Role.STUDENT);
        authenticate(expected);

        Object actual = resolver.resolveArgument(param("required"), null, null, null);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("Optional<Actor> 在已登录时给出非空值")
    void resolvesOptionalPresentWhenAuthenticated() {
        Actor expected = new Actor(2002L, "黄振亚", Role.TEACHER);
        authenticate(expected);

        Object actual = resolver.resolveArgument(param("optional"), null, null, null);

        assertThat(actual).isInstanceOf(Optional.class);
        assertThat(actual).isEqualTo(Optional.of(expected));
    }

    @Test
    @DisplayName("Optional<Actor> 在匿名时给出 empty 而不是抛异常")
    void resolvesOptionalEmptyWhenAnonymous() {
        // 这是 Optional<Actor> 存在的全部理由：匿名用户也能访问，控制器自己判空。
        SecurityContextHolder.clearContext();

        Object actual = resolver.resolveArgument(param("optional"), null, null, null);

        assertThat((Optional<?>) actual).isEmpty();
    }

    @Test
    @DisplayName("Actor 参数在匿名时抛 AuthenticationCredentialsNotFoundException")
    void throwsWhenAnonymous() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> resolver.resolveArgument(param("required"), null, null, null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessage("请先登录");
    }

    @Test
    @DisplayName("principal 不是 ActorAware 时，Actor 参数同样抛未登录异常")
    void throwsWhenPrincipalIsNotActorAware() {
        // 覆盖 L39 里 instanceof ActorAware 为假的分支：
        // 例如 OAuth2 或测试里塞了一个普通字符串 principal，不能被当成有效身份。
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "some-raw-principal", "n/a", List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(() -> resolver.resolveArgument(param("required"), null, null, null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    @DisplayName("principal 不是 ActorAware 时，Optional<Actor> 给出 empty 而不是抛异常")
    void optionalEmptyWhenPrincipalIsNotActorAware() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "some-raw-principal", "n/a", List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        Object actual = resolver.resolveArgument(param("optional"), null, null, null);

        assertThat((Optional<?>) actual).isEmpty();
    }

    /** 用实现了 {@link ActorAware} 的 principal 建立认证。 */
    private static void authenticate(Actor actor) {
        ActorAware principal = () -> actor;
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + actor.role().name())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
