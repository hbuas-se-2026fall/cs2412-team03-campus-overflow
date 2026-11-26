package com.campusoverflow.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 登录失败限流的边界测试（QS-08）。
 * 直接 mock StringRedisTemplate，不依赖真 Redis：断言的是计数 key、窗口只设一次、
 * 达到阈值的确切时刻，以及 reset 只清本人 key——这些是「改一个字就失效」的行为。
 * 异常到 429 的映射已由 GlobalExceptionHandlerTest / WebLayerTest 覆盖，此处不重复。
 */
@SuppressWarnings("unchecked")
class RedisLoginThrottleTest {

    private static final String USER = "20230001";
    private static final String KEY = "id:login-fail:" + USER;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private RedisLoginThrottle throttle;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        throttle = new RedisLoginThrottle(redis, 5);
    }

    @Test
    @DisplayName("首次失败写 id:login-fail:<用户> 并设 1 分钟窗口")
    void firstFailureStartsWindow() {
        when(ops.increment(KEY)).thenReturn(1L);

        throttle.recordFailure(USER);

        verify(ops).increment(KEY);
        verify(redis).expire(KEY, Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("后续失败只自增、不续期——固定窗口不能悄悄变成滑动窗口")
    void laterFailuresDoNotExtendWindow() {
        when(ops.increment(KEY)).thenReturn(2L, 3L);

        throttle.recordFailure(USER);
        throttle.recordFailure(USER);

        verify(ops, times(2)).increment(KEY);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("第 4 次不锁、第 5 次起锁——阈值边界不能差一")
    void blocksOnlyFromThreshold() {
        when(ops.get(KEY)).thenReturn("4", "5", "6");

        assertThat(throttle.isBlocked(USER)).isFalse();
        assertThat(throttle.isBlocked(USER)).isTrue();
        assertThat(throttle.isBlocked(USER)).isTrue();

        verify(ops, times(3)).get(KEY);
    }

    @Test
    @DisplayName("没有计数记录时不锁——正常登录不能被限流误伤")
    void absentKeyDoesNotBlock() {
        when(ops.get(KEY)).thenReturn(null);

        assertThat(throttle.isBlocked(USER)).isFalse();
    }

    @Test
    @DisplayName("reset 只删本人 key，不影响其他账号")
    void resetDeletesOwnKeyOnly() {
        throttle.reset(USER);

        verify(redis).delete(KEY);
        verify(redis, never()).delete("id:login-fail:20230002");
    }

    @Test
    @DisplayName("不同账号使用各自独立的计数 key")
    void keysAreNamespacedPerUser() {
        String otherKey = "id:login-fail:20230002";
        when(ops.increment(KEY)).thenReturn(1L);
        when(ops.increment(otherKey)).thenReturn(1L);

        throttle.recordFailure(USER);
        throttle.recordFailure("20230002");

        verify(redis).expire(KEY, Duration.ofMinutes(1));
        verify(redis).expire(otherKey, Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("increment 返回 null 时不设过期时间——异常计数不应留下无 TTL 的脏 key")
    void nullIncrementDoesNotSetExpiry() {
        when(ops.increment(KEY)).thenReturn(null);

        throttle.recordFailure(USER);

        verify(redis, never()).expire(anyString(), any(Duration.class));
    }
}
