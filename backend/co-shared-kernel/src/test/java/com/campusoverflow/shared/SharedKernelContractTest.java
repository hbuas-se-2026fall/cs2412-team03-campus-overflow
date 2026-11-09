package com.campusoverflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.shared.event.EventIds;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import com.campusoverflow.shared.util.Masking;
import com.campusoverflow.shared.util.UtcTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 共享内核里与框架无关的值对象 / 工具契约：分页、身份、角色、UTC 时间、事件 ID、脱敏。 */
class SharedKernelContractTest {

    @Test
    void pageRequestClampsLowerAndUpperBoundsAndComputesOffset() {
        assertThat(new PageRequest(0, 0)).isEqualTo(new PageRequest(1, 1));
        assertThat(new PageRequest(3, 999)).isEqualTo(new PageRequest(3, PageRequest.MAX_SIZE));
        assertThat(new PageRequest(3, 20).offset()).isEqualTo(40);
        assertThat(new PageRequest(-5, -1).offset()).isZero();
    }

    @Test
    void pageResultDefensivelyCopiesItemsAndMapsWithoutLosingPaging() {
        List<String> mutable = new ArrayList<>(List.of("a", "b"));
        PageResult<String> result = new PageResult<>(mutable, 5, 2, 2);
        mutable.add("c");

        assertThat(result.items()).containsExactly("a", "b");
        assertThatThrownBy(() -> result.items().add("d"))
                .isInstanceOf(UnsupportedOperationException.class);

        PageResult<Integer> mapped = result.map(String::length);
        assertThat(mapped.items()).containsExactly(1, 1);
        assertThat(mapped.total()).isEqualTo(5);
        assertThat(mapped.page()).isEqualTo(2);
        assertThat(mapped.size()).isEqualTo(2);
    }

    @Test
    void pageResultEmptyAndTotalPagesHandleZeroSize() {
        PageResult<String> empty = PageResult.empty(1, 10);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.total()).isZero();
        assertThat(empty.totalPages()).isZero();

        assertThat(new PageResult<>(List.of("a"), 21, 1, 10).totalPages()).isEqualTo(3);
        assertThat(new PageResult<>(List.of("a"), 20, 1, 10).totalPages()).isEqualTo(2);
        assertThat(new PageResult<>(List.of("a"), 1, 1, 0).totalPages()).isZero();
    }

    @Test
    void actorExposesRoleBasedChecks() {
        Actor student = new Actor(7L, "张三", Role.STUDENT);
        Actor teacher = new Actor(8L, "李老师", Role.TEACHER);
        Actor admin = new Actor(9L, "管理员", Role.ADMIN);

        assertThat(student.isAdmin()).isFalse();
        assertThat(student.isTeacherOrAdmin()).isFalse();
        assertThat(student.is(7L)).isTrue();
        assertThat(student.is(8L)).isFalse();

        assertThat(teacher.isAdmin()).isFalse();
        assertThat(teacher.isTeacherOrAdmin()).isTrue();

        assertThat(admin.isAdmin()).isTrue();
        assertThat(admin.isTeacherOrAdmin()).isTrue();
    }

    @Test
    void everyRoleHasAChineseLabel() {
        assertThat(Role.STUDENT.label()).isEqualTo("学生");
        assertThat(Role.TA.label()).isEqualTo("助教");
        assertThat(Role.TEACHER.label()).isEqualTo("教师");
        assertThat(Role.ADMIN.label()).isEqualTo("管理员");
        assertThat(Role.values()).hasSize(4);
    }

    @Test
    void utcTimeConvertsInBothDirectionsAndToleratesNull() {
        Instant instant = Instant.parse("2026-11-05T02:30:15.123456Z");
        LocalDateTime dbValue = UtcTime.toDb(instant);

        // 与 JVM 默认时区无关：数据库里存的是 UTC 墙钟时间。
        assertThat(dbValue).isEqualTo(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
        assertThat(UtcTime.fromDb(dbValue)).isEqualTo(instant);

        assertThat(UtcTime.toDb(null)).isNull();
        assertThat(UtcTime.fromDb(null)).isNull();
    }

    @Test
    void eventIdsAreUniqueParseableUuids() {
        String first = EventIds.next();
        String second = EventIds.next();

        assertThat(UUID.fromString(first)).isNotNull();
        assertThat(UUID.fromString(second)).isNotNull();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void maskingHandlesNullShortAndMalformedInputs() {
        assertThat(Masking.account(null)).isEqualTo("****");
        assertThat(Masking.account("123456")).isEqualTo("****");
        assertThat(Masking.account("2023001217")).isEqualTo("2023****17");

        assertThat(Masking.email(null)).isEqualTo("***");
        assertThat(Masking.email("not-an-email")).isEqualTo("***");
        assertThat(Masking.email("alice@campus.edu")).isEqualTo("a***@campus.edu");
    }
}
