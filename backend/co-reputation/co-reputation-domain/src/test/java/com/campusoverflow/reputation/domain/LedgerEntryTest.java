package com.campusoverflow.reputation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 账本条目：只追加、不可变，事件 id 与原因不能为空（幂等消费依赖这两个字段做去重键）。 */
class LedgerEntryTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

    @Test
    void 新建条目没有主键且字段完整() {
        LedgerEntry entry = LedgerEntry.of(9L, "evt-1", ReputationReason.ANSWER_ACCEPTED, 15, "ANSWER", 42L, NOW);

        assertThat(entry.id()).isNull();
        assertThat(entry.userId()).isEqualTo(9L);
        assertThat(entry.eventId()).isEqualTo("evt-1");
        assertThat(entry.reason()).isEqualTo(ReputationReason.ANSWER_ACCEPTED);
        assertThat(entry.delta()).isEqualTo(15);
        assertThat(entry.refType()).isEqualTo("ANSWER");
        assertThat(entry.refId()).isEqualTo(42L);
        assertThat(entry.createdAt()).isEqualTo(NOW);
    }

    @Test
    void 事件id不能为空否则无法做幂等去重() {
        assertThatThrownBy(() -> LedgerEntry.of(9L, null, ReputationReason.ANSWER_VOTE, 10, "ANSWER", 42L, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 原因不能为空() {
        assertThatThrownBy(() -> LedgerEntry.of(9L, "evt-2", null, 10, "ANSWER", 42L, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 允许负增量表示扣减() {
        LedgerEntry entry = LedgerEntry.of(9L, "evt-3", ReputationReason.QUESTION_VOTE, -2, "QUESTION", 7L, NOW);

        assertThat(entry.delta()).isNegative();
    }

    @Test
    void 重建条目可以带主键() {
        LedgerEntry entry = new LedgerEntry(1L, 9L, "evt-4", ReputationReason.BOUNTY_AWARDED, 50, "QUESTION", 7L, NOW);

        assertThat(entry.id()).isEqualTo(1L);
        assertThat(entry.reason()).isEqualTo(ReputationReason.BOUNTY_AWARDED);
    }
}
