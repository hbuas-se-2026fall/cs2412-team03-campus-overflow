package com.campusoverflow.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.discovery.domain.search.SearchHit;
import com.campusoverflow.discovery.domain.search.SearchIndex.RelatedCandidate;
import com.campusoverflow.discovery.domain.search.SearchQuery;
import com.campusoverflow.shared.page.PageResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private static SearchHit hit(long questionId, long authorId) {
        return new SearchHit(questionId, "标题" + questionId, "摘要", List.of("数据结构"), 7L, authorId,
                3, 2, false, false, NOW);
    }

    private static SearchQuery query() {
        return new SearchQuery("跳表", null, null, null, false, false, null);
    }

    @Test
    void searchMapsHitsAndLoadsAuthorsInOneBatch() {
        var identities = new DiscoveryFakes.Identities().add(11, "小明").add(12, "小红");
        var service = new DiscoveryQueryService(
                q -> new PageResult<>(List.of(hit(1, 11), hit(2, 11), hit(3, 12)), 3, 1, 20),
                new DiscoveryFakes.SearchIndexes(), identities);

        PageResult<SearchResultView> result = service.search(query());

        assertThat(result.items()).hasSize(3);
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.items().get(0).id()).isEqualTo(1);
        assertThat(result.items().get(0).excerpt()).isEqualTo("摘要");
        assertThat(result.items().get(0).author().displayName()).isEqualTo("小明");
        assertThat(result.items().get(0).author().role()).isEqualTo("STUDENT");
        assertThat(result.items().get(0).author().verified()).isTrue();
        assertThat(result.items().get(2).author().displayName()).isEqualTo("小红");
        // 一次批量查询且作者 ID 去重，避免逐条查库造成 N+1
        assertThat(identities.batchQueries).hasSize(1);
        assertThat(identities.batchQueries.get(0)).containsExactly(11L, 12L);
    }

    @Test
    void emptySearchSkipsIdentityLookupEntirely() {
        var identities = new DiscoveryFakes.Identities().add(11, "小明");
        var service = new DiscoveryQueryService(q -> PageResult.empty(1, 20),
                new DiscoveryFakes.SearchIndexes(), identities);

        assertThat(service.search(query()).items()).isEmpty();
        assertThat(identities.batchQueries).isEmpty();
    }

    @Test
    void missingAuthorFallsBackToAnonymousPlaceholder() {
        var service = new DiscoveryQueryService(q -> new PageResult<>(List.of(hit(1, 999)), 1, 1, 20),
                new DiscoveryFakes.SearchIndexes(), new DiscoveryFakes.Identities());

        SearchResultView.Author author = service.search(query()).items().get(0).author();

        assertThat(author.id()).isEqualTo(999);
        assertThat(author.displayName()).isEqualTo("已注销用户");
        assertThat(author.role()).isEqualTo("STUDENT");
        assertThat(author.verified()).isFalse();
    }

    @Test
    void relatedRanksByJaccardSimilarityAndHonoursLimit() {
        var index = new DiscoveryFakes.SearchIndexes();
        index.tags = List.of("a", "b");
        index.candidates = List.of(
                new RelatedCandidate(10, "低相似", 1, 5, 99, 3, true),  // J = 1/6
                new RelatedCandidate(20, "高相似", 2, 2, 0, 0, false),  // J = 2/2 = 1.0
                new RelatedCandidate(30, "中相似", 2, 3, 5, 1, true));  // J = 2/3
        var service = new DiscoveryQueryService(q -> PageResult.empty(1, 20), index,
                new DiscoveryFakes.Identities());

        List<RelatedQuestionView> views = service.related(1, 2);

        assertThat(views).extracting(RelatedQuestionView::id).containsExactly(20L, 30L);
        assertThat(views.get(0).similarity()).isEqualTo(1.0);
        assertThat(views.get(1).similarity()).isEqualTo(2.0 / 3.0);
        assertThat(index.requestedTags).containsExactly("a", "b");
        // 候选数放大后再由领域策略重排，避免 SQL 粗排序截断高相似题目
        assertThat(index.requestedLimit).isGreaterThan(2);
    }

    @Test
    void relatedIsEmptyForNoTagsOrNonPositiveLimit() {
        var index = new DiscoveryFakes.SearchIndexes();
        var service = new DiscoveryQueryService(q -> PageResult.empty(1, 20), index,
                new DiscoveryFakes.Identities());

        assertThat(service.related(1, 5)).isEmpty();
        index.tags = List.of("a");
        assertThat(service.related(1, 0)).isEmpty();
        assertThat(service.related(1, -1)).isEmpty();
        assertThat(index.requestedLimit).isZero();
    }
}
