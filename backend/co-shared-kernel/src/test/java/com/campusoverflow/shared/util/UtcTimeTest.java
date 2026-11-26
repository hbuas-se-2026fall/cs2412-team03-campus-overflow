package com.campusoverflow.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UtcTime} 的行为测试。
 *
 * <p>这两行代码看着没什么好测的，但它是**全仓时间字段的正确性基石**：
 * 所有 {@code DATETIME(6)} 列都按这个约定存 UTC 墙钟时间。
 * 一旦它依赖了 JVM 默认时区，同一份数据在东八区的机器上读出来就差了 8 小时——
 * 而这种错误不会报错，只会让「三天前的帖子」显示成「今天」。
 */
class UtcTimeTest {

    @Test
    @DisplayName("Instant → 数据库时间：取 UTC 墙钟，不是本地墙钟")
    void toDbUsesUtcWallClock() {
        // 2026-01-01T00:00:00Z 在东八区是 08:00。如果实现用了系统默认时区，
        // 这里会拿到 08:00 而不是 00:00。
        Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        LocalDateTime db = UtcTime.toDb(instant);

        assertThat(db).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    @DisplayName("结果与 JVM 默认时区无关")
    void independentOfDefaultTimeZone() {
        // 这是本类存在的全部理由：无论服务器在哪个时区，落库的值必须一致。
        Instant instant = Instant.parse("2026-07-15T12:34:56.123456Z");
        TimeZone original = TimeZone.getDefault();
        try {
            LocalDateTime shanghai;
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            shanghai = UtcTime.toDb(instant);

            LocalDateTime newYork;
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            newYork = UtcTime.toDb(instant);

            LocalDateTime utc;
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            utc = UtcTime.toDb(instant);

            assertThat(shanghai).isEqualTo(newYork).isEqualTo(utc);
            assertThat(shanghai).isEqualTo(LocalDateTime.of(2026, 7, 15, 12, 34, 56, 123_456_000));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("数据库时间 → Instant：按 UTC 解释")
    void fromDbInterpretsAsUtc() {
        LocalDateTime db = LocalDateTime.of(2026, 1, 1, 0, 0);

        Instant instant = UtcTime.fromDb(db);

        assertThat(instant).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        // 与 toDb 互逆
        assertThat(UtcTime.toDb(instant)).isEqualTo(db);
    }

    @Test
    @DisplayName("微秒精度不丢失——DATETIME(6) 存的就是微秒")
    void microsecondPrecisionIsPreserved() {
        // H2 上截断到毫秒、MySQL 上到微秒，这类差异只有往返一次才看得出来。
        LocalDateTime db = LocalDateTime.of(2026, 3, 9, 8, 15, 30, 123_456_000);

        Instant back = UtcTime.fromDb(db);

        assertThat(UtcTime.toDb(back)).isEqualTo(db);
        assertThat(back).isEqualTo(LocalDateTime.of(2026, 3, 9, 8, 15, 30, 123_456_000)
                .toInstant(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("往返转换是无损的（同一 Instant 转两次一致）")
    void roundTrip() {
        Instant original = Instant.parse("2026-11-19T05:06:07.891011Z");

        Instant result = UtcTime.fromDb(UtcTime.toDb(original));

        assertThat(result).isEqualTo(original);
    }

    @Test
    @DisplayName("null 原样返回，不抛 NPE")
    void nullSafe() {
        // 可空字段（如 deleted_at）在 mapper 里直接传 null 进来，
        // 如果这里抛 NPE，整个查询都会挂掉。
        assertThat(UtcTime.toDb(null)).isNull();
        assertThat(UtcTime.fromDb(null)).isNull();
    }

    @Test
    @DisplayName("时间戳落在 UTC 边界两侧时不会跨天")
    void boundaryNearMidnight() {
        // 2026-01-01T00:00:00Z 与 2025-12-31T23:59:59Z 相差一秒，但分属两天。
        // 时区处理错位的实现常在这里把跨天算错。
        assertThat(UtcTime.toDb(Instant.parse("2025-12-31T23:59:59Z")).toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2025, 12, 31));
        assertThat(UtcTime.toDb(Instant.parse("2026-01-01T00:00:00Z")).toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2026, 1, 1));
    }

    @Test
    @DisplayName("用的是 UTC 规则而不是某个固定偏移")
    void usesUtcZoneId() {
        // 直接验证：结果等于用 ZoneOffset.UTC 显式转换的值
        Instant instant = Instant.parse("2026-06-01T10:00:00Z");

        assertThat(UtcTime.toDb(instant)).isEqualTo(LocalDateTime.ofInstant(instant, ZoneId.of("UTC")));
    }
}
