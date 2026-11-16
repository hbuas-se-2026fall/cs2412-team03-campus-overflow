package com.campusoverflow.reputation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 学期 / 周 / 月的口径。这是「排行榜按周/按月/按学期统计」的底座，
 * 之前完全没有测试：时区、1 月归属上一学年、ISO 周跨年这三处最容易错。
 */
class AcademicTermTest {

    @Test
    void 二月到七月属于春季学期() {
        assertThat(AcademicTerm.termKey(Instant.parse("2026-02-01T00:00:00Z"))).isEqualTo("2026-SPRING");
        assertThat(AcademicTerm.termKey(Instant.parse("2026-07-31T00:00:00Z"))).isEqualTo("2026-SPRING");
    }

    @Test
    void 八月到十二月属于当年秋季学期() {
        assertThat(AcademicTerm.termKey(Instant.parse("2026-08-01T00:00:00Z"))).isEqualTo("2026-FALL");
        assertThat(AcademicTerm.termKey(Instant.parse("2026-12-31T00:00:00Z"))).isEqualTo("2026-FALL");
    }

    @Test
    void 一月仍算上一年的秋季学期() {
        assertThat(AcademicTerm.termKey(Instant.parse("2026-01-15T00:00:00Z"))).isEqualTo("2025-FALL");
    }

    @Test
    void 按校园时区而不是UTC判定学期() {
        // UTC 还是 1 月 31 日，上海已经是 2 月 1 日 01:00 → 属于 2026 春季学期
        assertThat(AcademicTerm.termKey(Instant.parse("2026-01-31T17:00:00Z"))).isEqualTo("2026-SPRING");
        // UTC 1 月 31 日 15:59 = 上海 23:59 → 仍是 1 月，算 2025 秋季学期
        assertThat(AcademicTerm.termKey(Instant.parse("2026-01-31T15:59:00Z"))).isEqualTo("2025-FALL");
    }

    @Test
    void 周键使用ISO周而不是自然周() {
        // 2025-12-29 是周一，ISO 周属于 2026 年第 1 周
        assertThat(AcademicTerm.weekKey(Instant.parse("2025-12-29T02:00:00Z"))).isEqualTo("2026-W01");
        assertThat(AcademicTerm.weekKey(Instant.parse("2026-03-05T00:00:00Z"))).isEqualTo("2026-W10");
    }

    @Test
    void 周键补零到两位() {
        assertThat(AcademicTerm.weekKey(Instant.parse("2026-01-01T02:00:00Z"))).isEqualTo("2026-W01");
    }

    @Test
    void 月键补零到两位() {
        assertThat(AcademicTerm.monthKey(Instant.parse("2026-03-05T00:00:00Z"))).isEqualTo("2026-03");
        assertThat(AcademicTerm.monthKey(Instant.parse("2026-09-30T00:00:00Z"))).isEqualTo("2026-09");
        assertThat(AcademicTerm.monthKey(Instant.parse("2026-12-01T00:00:00Z"))).isEqualTo("2026-12");
    }

    @Test
    void 月键同样按校园时区判定() {
        // UTC 12 月 31 日 16:30 = 上海 1 月 1 日 00:30
        assertThat(AcademicTerm.monthKey(Instant.parse("2026-12-31T16:30:00Z"))).isEqualTo("2027-01");
    }

    @Test
    void 校园时区固定为上海() {
        assertThat(AcademicTerm.CAMPUS_ZONE.getId()).isEqualTo("Asia/Shanghai");
    }
}
