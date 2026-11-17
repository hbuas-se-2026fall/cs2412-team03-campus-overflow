package com.campusoverflow.discovery.application;

import com.campusoverflow.discovery.domain.search.RelatedQuestionPolicy;
import com.campusoverflow.discovery.domain.search.SearchHit;
import com.campusoverflow.discovery.domain.search.SearchIndex;
import com.campusoverflow.discovery.domain.search.SearchPort;
import com.campusoverflow.discovery.domain.search.SearchQuery;
import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Role;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用例：搜索与筛选、相关问题推荐。 */
@Service
@Transactional(readOnly = true)
public class DiscoveryQueryService {

    /** 先多取候选再按 Jaccard 重排：SQL 只按共享标签数粗排，直接截断会漏掉高相似度题目。 */
    private static final int CANDIDATE_FACTOR = 5;
    private static final int MAX_CANDIDATES = 100;

    private final SearchPort search;
    private final SearchIndex index;
    private final IdentityApi identity;

    public DiscoveryQueryService(SearchPort search, SearchIndex index, IdentityApi identity) {
        this.search = search;
        this.index = index;
        this.identity = identity;
    }

    public PageResult<SearchResultView> search(SearchQuery query) {
        PageResult<SearchHit> hits = search.search(query);
        if (hits.items().isEmpty()) {
            // 不把空列表交给 IdentityApi，避免产生 IN () 之类的空集合查询
            return PageResult.empty(hits.page(), hits.size());
        }
        List<Long> authorIds = hits.items().stream().map(SearchHit::authorId).distinct().toList();
        Map<Long, UserSummary> authors = identity.findUsers(authorIds);
        return hits.map(hit -> toView(hit, authors.get(hit.authorId())));
    }

    public List<RelatedQuestionView> related(long questionId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> tags = index.findTags(questionId);
        if (tags.isEmpty()) {
            return List.of();
        }
        int candidateLimit = (int) Math.min(Math.max((long) limit * CANDIDATE_FACTOR, limit), MAX_CANDIDATES);
        List<SearchIndex.RelatedCandidate> candidates =
                index.findCandidatesSharingTags(questionId, tags, candidateLimit);
        if (candidates.isEmpty()) {
            return List.of();
        }
        int ownTagCount = tags.size();
        return RelatedQuestionPolicy.rank(candidates, ownTagCount, limit).stream()
                .map(c -> new RelatedQuestionView(c.questionId(), c.title(), c.answerCount(), c.accepted(),
                        RelatedQuestionPolicy.jaccard(c.sharedTags(), ownTagCount, c.tagCount())))
                .toList();
    }

    private static SearchResultView toView(SearchHit hit, UserSummary author) {
        SearchResultView.Author view = new SearchResultView.Author(
                hit.authorId(),
                author == null ? "已注销用户" : author.displayName(),
                author == null ? Role.STUDENT.name() : author.role().name(),
                author != null && author.verified());
        return new SearchResultView(hit.questionId(), hit.title(), hit.snippet(), hit.tags(), hit.courseId(),
                view, hit.score(), hit.answerCount(), hit.accepted(), hit.bountyOpen(), hit.createdAt());
    }
}
