package com.campusoverflow.discovery.domain.search;

import java.util.Comparator;
import java.util.List;

/**
 * 相关问题推荐（规则引擎，非 AI）：按标签集合的 Jaccard 相似度排序，
 * J(A,B) = |A∩B| / |A∪B|；相似度相同则得分高、已采纳者优先。
 */
public final class RelatedQuestionPolicy {

    private RelatedQuestionPolicy() {
    }

    /** J(A,B) = |A∩B| / |A∪B|，其中 |A∪B| = |A| + |B| − |A∩B|。两侧都为空时视为不相似。 */
    public static double jaccard(int shared, int sizeA, int sizeB) {
        int union = sizeA + sizeB - shared;
        if (union <= 0) {
            return 0.0;
        }
        return (double) shared / union;
    }

    /**
     * 排序规则：相似度降序 → 得分降序 → 已采纳优先 → 问题 ID 升序（保证结果稳定可复现）。
     */
    public static List<SearchIndex.RelatedCandidate> rank(List<SearchIndex.RelatedCandidate> candidates, int ownTagCount,
                                                          int limit) {
        Comparator<SearchIndex.RelatedCandidate> bySimilarity = Comparator
                .comparingDouble((SearchIndex.RelatedCandidate c) -> jaccard(c.sharedTags(), ownTagCount, c.tagCount()))
                .reversed();
        Comparator<SearchIndex.RelatedCandidate> byScore =
                Comparator.comparingInt(SearchIndex.RelatedCandidate::score).reversed();
        Comparator<SearchIndex.RelatedCandidate> byAccepted =
                Comparator.comparing(SearchIndex.RelatedCandidate::accepted).reversed();

        return candidates.stream()
                .sorted(bySimilarity.thenComparing(byScore).thenComparing(byAccepted)
                        .thenComparingLong(SearchIndex.RelatedCandidate::questionId))
                .limit(limit)
                .toList();
    }
}
