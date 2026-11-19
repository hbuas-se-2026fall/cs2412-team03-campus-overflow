package com.campusoverflow.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.shared.page.PageResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 补测 {@link DiscoveryQueryService#related(long, int)} 的「有标签、limit 合法，
 * 但没有任何共享标签候选」分支（DiscoveryQueryService.java 第 58-60 行）。
 *
 * <p>原有 {@code relatedIsEmptyForNoTagsOrNonPositiveLimit} 只覆盖了 tags 为空与 limit<=0 两种提前返回，
 * 这条候选为空的分支此前没有断言保护：若有人误删 {@code if (candidates.isEmpty()) return List.of();}，
 * 下游 {@code RelatedQuestionPolicy.rank} 对空列表或 {@code ownTagCount} 的处理一旦变化就不会被测试发现。
 */
class DiscoveryRelatedNoCandidateTest {

    @Test
    void relatedIsEmptyWhenCandidatesMissingButTagsPresent() {
        var index = new DiscoveryFakes.SearchIndexes();
        index.tags = List.of("mysql", "B+树"); // 问题有标签
        index.candidates = List.of();          // 但索引里没有任何共享标签的候选
        var service = new DiscoveryQueryService(q -> PageResult.empty(1, 20), index,
                new DiscoveryFakes.Identities());

        List<RelatedQuestionView> views = service.related(1, 5);

        assertThat(views).isEmpty();
        // 确认确实走到了候选查询这一步，而不是在 tags/limit 前置校验就返回
        assertThat(index.requestedTags).containsExactly("mysql", "B+树");
        // 候选数放大因子生效（limit=5 -> CANDIDATE_FACTOR=5 -> 25）
        assertThat(index.requestedLimit).isGreaterThan(5);
    }
}
