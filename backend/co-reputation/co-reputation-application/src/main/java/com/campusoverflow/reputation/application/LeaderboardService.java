package com.campusoverflow.reputation.application;

import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.reputation.api.event.ReputationChangedEvent;
import com.campusoverflow.reputation.domain.ReputationAccountRepository;
import com.campusoverflow.shared.event.ProcessedEventStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 排行榜：周/月/学期榜来自 Redis ZSET，总榜直接读账户表。 */
@Service
public class LeaderboardService {

    /** 消费端标识，写入 processed_event 的 consumer 列，用于重复投递去重。 */
    static final String CONSUMER = "leaderboard";

    private static final int MAX_LIMIT = 100;
    private static final String UNKNOWN_USER = "未知用户";

    private final LeaderboardStore store;
    private final ReputationAccountRepository accounts;
    private final IdentityApi identity;
    private final ProcessedEventStore processed;
    private final Clock clock;

    public LeaderboardService(LeaderboardStore store, ReputationAccountRepository accounts, IdentityApi identity,
                              ProcessedEventStore processed, Clock clock) {
        this.store = store;
        this.accounts = accounts;
        this.identity = identity;
        this.processed = processed;
        this.clock = clock;
    }

    /**
     * 声誉变化事件 → 三个周期的 ZSET 各加一次增量。
     * 发件箱是<b>至少一次</b>语义，所以先用 {@link ProcessedEventStore} 去重：
     * 同一 eventId 第二次到达时直接跳过，否则改投/重试会把分数加两遍。
     */
    @EventListener
    @Transactional
    public void on(ReputationChangedEvent e) {
        if (!processed.markProcessed(e.eventId(), CONSUMER)) {
            return;
        }
        Instant at = e.occurredAt() == null ? clock.instant() : e.occurredAt();
        store.increment(e.userId(), e.delta(), at);
    }

    /**
     * 排行榜前 N 名。{@code ALL} 走账户表，其余周期走 Redis ZSET；
     * 只展示正分（负分来自被踩，出现在榜单上既没意义也会把名次算乱），
     * 名次在过滤之后重排，保证「第 1 名」一定是可见的第一条。
     */
    @Transactional(readOnly = true)
    public List<LeaderboardEntryView> top(LeaderboardPeriod period, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int size = Math.min(limit, MAX_LIMIT);
        List<LeaderboardStore.Entry> entries = period == LeaderboardPeriod.ALL
                ? totalBoard(size)
                : store.top(period, clock.instant(), size);

        List<LeaderboardStore.Entry> ranked = entries.stream()
                .filter(e -> e.score() > 0)
                .toList();
        if (ranked.isEmpty()) {
            return List.of();
        }

        Map<Long, UserSummary> users =
                identity.findUsers(ranked.stream().map(LeaderboardStore.Entry::userId).distinct().toList());
        List<LeaderboardEntryView> views = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            LeaderboardStore.Entry entry = ranked.get(i);
            UserSummary user = users.get(entry.userId());
            views.add(new LeaderboardEntryView(i + 1, entry.userId(),
                    user == null ? UNKNOWN_USER : user.displayName(),
                    user != null && user.verified(),
                    entry.score()));
        }
        return List.copyOf(views);
    }

    /** 总榜：账户表按声誉倒序；仓储只负责取 top，这里再兜一次排序与过滤。 */
    private List<LeaderboardStore.Entry> totalBoard(int size) {
        return accounts.topByReputation(size).stream()
                .map(a -> new LeaderboardStore.Entry(a.userId(), a.reputation()))
                .sorted(Comparator.comparingLong(LeaderboardStore.Entry::score).reversed())
                .toList();
    }
}
