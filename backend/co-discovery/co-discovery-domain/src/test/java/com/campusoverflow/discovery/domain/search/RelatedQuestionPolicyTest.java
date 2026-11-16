package com.campusoverflow.discovery.domain.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.discovery.domain.search.SearchIndex.RelatedCandidate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 相关问题排序：Jaccard 相似度 → 分数 → 是否已采纳 → 题号。
 * 这四条比较链决定了「相关问题」区块里出现什么，顺序错了用户会觉得莫名其妙。
 */
class RelatedQuestionPolicyTest {

    private static RelatedCandidate candidate(long id, int sharedTags, int tagCount, int score, boolean accepted) {
        return new RelatedCandidate(id, "题" + id, sharedTags, tagCount, score, 0, accepted);
    }

    @Test
    void jaccard按交集除并集计算() {
        assertThat(RelatedQuestionPolicy.jaccard(2, 3, 3)).isEqualTo(0.5);
        assertThat(RelatedQuestionPolicy.jaccard(3, 3, 3)).isEqualTo(1.0);
        assertThat(RelatedQuestionPolicy.jaccard(0, 2, 3)).isZero();
    }

    @Test
    void 两边都没有标签时相似度为0而不是除零异常() {
        assertThat(RelatedQuestionPolicy.jaccard(0, 0, 0)).isZero();
    }

    @Test
    void 按相似度从高到低排序() {
        List<RelatedCandidate> ranked = RelatedQuestionPolicy.rank(List.of(
                candidate(1, 1, 5, 100, true),
                candidate(2, 3, 3, 0, false),
                candidate(3, 2, 4, 10, true)
        ), 4, 10);

        assertThat(ranked).extracting(RelatedCandidate::questionId).containsExactly(2L, 3L, 1L);
    }

    @Test
    void 相似度相同时分数高的在前() {
        List<RelatedCandidate> ranked = RelatedQuestionPolicy.rank(List.of(
                candidate(1, 2, 4, 5, true),
                candidate(2, 2, 4, 50, false)
        ), 4, 10);

        assertThat(ranked).extracting(RelatedCandidate::questionId).containsExactly(2L, 1L);
    }

    @Test
    void 相似度与分数都相同时已采纳的在前() {
        List<RelatedCandidate> ranked = RelatedQuestionPolicy.rank(List.of(
                candidate(1, 2, 4, 5, false),
                candidate(2, 2, 4, 5, true)
        ), 4, 10);

        assertThat(ranked).extracting(RelatedCandidate::questionId).containsExactly(2L, 1L);
    }

    @Test
    void 全部相同时按题号升序保证结果稳定() {
        List<RelatedCandidate> ranked = RelatedQuestionPolicy.rank(List.of(
                candidate(9, 2, 4, 5, true),
                candidate(3, 2, 4, 5, true),
                candidate(7, 2, 4, 5, true)
        ), 4, 10);

        assertThat(ranked).extracting(RelatedCandidate::questionId).containsExactly(3L, 7L, 9L);
    }

    @Test
    void limit生效且不超过候选数量() {
        List<RelatedCandidate> candidates = List.of(
                candidate(1, 3, 3, 0, false),
                candidate(2, 2, 3, 0, false),
                candidate(3, 1, 3, 0, false)
        );

        assertThat(RelatedQuestionPolicy.rank(candidates, 3, 2)).hasSize(2);
        assertThat(RelatedQuestionPolicy.rank(candidates, 3, 10)).hasSize(3);
        assertThat(RelatedQuestionPolicy.rank(List.of(), 3, 5)).isEmpty();
    }
}
