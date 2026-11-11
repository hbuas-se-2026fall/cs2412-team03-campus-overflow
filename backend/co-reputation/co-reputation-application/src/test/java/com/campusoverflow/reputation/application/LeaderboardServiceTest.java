package com.campusoverflow.reputation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.identity.api.CourseSummary;
import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.reputation.api.event.ReputationChangedEvent;
import com.campusoverflow.reputation.domain.ReputationAccount;
import com.campusoverflow.reputation.domain.ReputationAccountRepository;
import com.campusoverflow.shared.event.ProcessedEventStore;
import com.campusoverflow.shared.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 排行榜：事件增量必须「每事件一次」，榜单必须按分数给名次并对齐用户信息。 */
class LeaderboardServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static ReputationChangedEvent event(String id, long userId, int delta) {
        return new ReputationChangedEvent(id, NOW, userId, delta, 15, "ANSWER_ACCEPTED");
    }

    @Test
    void incrementsStoreOncePerEventAndSkipsDuplicateDelivery() {
        var store = new FakeStore();
        var processed = new FakeProcessed();
        var service = new LeaderboardService(store, new FakeAccounts(Map.of()), new FakeIdentity(Map.of()), processed, CLOCK);

        service.on(event("e1", 7, 15));
        service.on(event("e1", 7, 15)); // 至少一次投递的重复
        service.on(event("e2", 7, -2));

        assertThat(store.increments).containsExactly(new FakeStore.Call(7, 15, NOW), new FakeStore.Call(7, -2, NOW));
        assertThat(processed.marked).containsExactly("e1", "e2");
    }

    @Test
    void weeklyBoardRanksEntriesAndResolvesDisplayNames() {
        var store = new FakeStore();
        store.weekEntries = List.of(new LeaderboardStore.Entry(7, 150), new LeaderboardStore.Entry(8, 90));
        var identity = new FakeIdentity(Map.of(
                7L, new UserSummary(7, "小红", Role.STUDENT, true, "计算机工程学院"),
                8L, new UserSummary(8, "小刚", Role.STUDENT, false, "计算机工程学院")));
        var service = new LeaderboardService(store, new FakeAccounts(Map.of()), identity, new FakeProcessed(), CLOCK);

        List<LeaderboardEntryView> top = service.top(LeaderboardPeriod.WEEK, 10);

        assertThat(top).hasSize(2);
        assertThat(top.get(0)).isEqualTo(new LeaderboardEntryView(1, 7, "小红", true, 150));
        assertThat(top.get(1)).isEqualTo(new LeaderboardEntryView(2, 8, "小刚", false, 90));
    }

    @Test
    void allBoardReadsAccountsSortsDescendingAndFiltersNonPositiveScores() {
        var accounts = new FakeAccounts(new LinkedHashMap<>(Map.of(3L, 40, 4L, 0)));
        accounts.reputations.put(5L, 120);
        var identity = new FakeIdentity(Map.of(5L, new UserSummary(5, "王老师", Role.TEACHER, true, "计算机工程学院")));
        var service = new LeaderboardService(new FakeStore(), accounts, identity, new FakeProcessed(), CLOCK);

        List<LeaderboardEntryView> top = service.top(LeaderboardPeriod.ALL, 10);

        assertThat(top).extracting(LeaderboardEntryView::userId).containsExactly(5L, 3L);
        assertThat(top.get(0).rank()).isEqualTo(1);
        assertThat(top.get(0).score()).isEqualTo(120);
        assertThat(top.get(1).displayName()).isEqualTo("未知用户"); // 用户信息缺失时不隐藏榜单，只降级名字
    }

    @Test
    void unknownUsersAreShownWithFallbackNameAndNonPositiveLimitReturnsEmpty() {
        var store = new FakeStore();
        store.weekEntries = List.of(new LeaderboardStore.Entry(99, 30));
        var service = new LeaderboardService(store, new FakeAccounts(Map.of()), new FakeIdentity(Map.of()), new FakeProcessed(), CLOCK);

        assertThat(service.top(LeaderboardPeriod.WEEK, 5))
                .containsExactly(new LeaderboardEntryView(1, 99, "未知用户", false, 30));
        assertThat(service.top(LeaderboardPeriod.WEEK, 0)).isEmpty();
        assertThat(service.top(LeaderboardPeriod.WEEK, -1)).isEmpty();
    }

    @Test
    void limitIsCappedSoACallerCannotAskForUnboundedScan() {
        var store = new FakeStore();
        store.weekEntries = List.of();
        var service = new LeaderboardService(store, new FakeAccounts(Map.of()), new FakeIdentity(Map.of()), new FakeProcessed(), CLOCK);

        service.top(LeaderboardPeriod.WEEK, 10_000);

        assertThat(store.lastLimit).isEqualTo(100);
    }

    // ---------------- fakes ----------------

    private static final class FakeStore implements LeaderboardStore {
        record Call(long userId, int delta, Instant at) {
        }

        final List<Call> increments = new ArrayList<>();
        List<Entry> weekEntries = List.of();
        int lastLimit = -1;

        @Override
        public void increment(long userId, int delta, Instant at) {
            increments.add(new Call(userId, delta, at));
        }

        @Override
        public List<Entry> top(LeaderboardPeriod period, Instant at, int limit) {
            lastLimit = limit;
            return weekEntries;
        }
    }

    private static final class FakeProcessed implements ProcessedEventStore {
        final Set<String> marked = new HashSet<>();

        @Override
        public boolean markProcessed(String eventId, String consumer) {
            return marked.add(eventId);
        }
    }

    private static final class FakeAccounts implements ReputationAccountRepository {
        final Map<Long, Integer> reputations = new LinkedHashMap<>();

        FakeAccounts(Map<Long, Integer> seed) {
            reputations.putAll(seed);
        }

        @Override
        public Optional<ReputationAccount> findByUserId(long userId) {
            return Optional.ofNullable(reputations.get(userId))
                    .map(r -> ReputationAccount.reconstitute(userId, r, 0, 0, 0));
        }

        @Override
        public List<ReputationAccount> findAllByUserId(Collection<Long> userIds) {
            List<ReputationAccount> result = new ArrayList<>();
            userIds.forEach(id -> findByUserId(id).ifPresent(result::add));
            return result;
        }

        @Override
        public List<ReputationAccount> topByReputation(int limit) {
            return findAll().stream().limit(limit).toList();
        }

        @Override
        public List<ReputationAccount> findAll() {
            return reputations.entrySet().stream()
                    .map(e -> ReputationAccount.reconstitute(e.getKey(), e.getValue(), 0, 0, 0))
                    .toList();
        }

        @Override
        public ReputationAccount save(ReputationAccount account) {
            reputations.put(account.userId(), account.reputation());
            return account;
        }
    }

    private static final class FakeIdentity implements IdentityApi {
        private final Map<Long, UserSummary> users;

        FakeIdentity(Map<Long, UserSummary> users) {
            this.users = users;
        }

        @Override
        public Optional<UserSummary> findUser(long userId) {
            return Optional.ofNullable(users.get(userId));
        }

        @Override
        public Map<Long, UserSummary> findUsers(Collection<Long> userIds) {
            Map<Long, UserSummary> result = new LinkedHashMap<>();
            userIds.forEach(id -> findUser(id).ifPresent(u -> result.put(id, u)));
            return result;
        }

        @Override
        public Optional<UserSummary> findByDisplayName(String displayName) {
            return users.values().stream().filter(u -> u.displayName().equals(displayName)).findFirst();
        }

        @Override
        public Optional<CourseSummary> findCourse(long courseId) {
            return Optional.empty();
        }

        @Override
        public boolean canManageCourse(long userId, Long courseId) {
            return false;
        }
    }
}
