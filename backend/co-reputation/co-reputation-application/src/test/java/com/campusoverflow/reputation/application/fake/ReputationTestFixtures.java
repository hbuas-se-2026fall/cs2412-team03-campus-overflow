package com.campusoverflow.reputation.application.fake;

import com.campusoverflow.identity.api.CourseSummary;
import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.qa.api.QaQueryApi;
import com.campusoverflow.qa.api.QuestionRef;
import com.campusoverflow.reputation.domain.BadgeAward;
import com.campusoverflow.reputation.domain.BadgeRepository;
import com.campusoverflow.reputation.domain.BadgeType;
import com.campusoverflow.reputation.domain.Bounty;
import com.campusoverflow.reputation.domain.BountyRepository;
import com.campusoverflow.reputation.domain.BountyStatus;
import com.campusoverflow.reputation.domain.LedgerEntry;
import com.campusoverflow.reputation.domain.LedgerRepository;
import com.campusoverflow.reputation.domain.ReputationAccount;
import com.campusoverflow.reputation.domain.ReputationAccountRepository;
import com.campusoverflow.reputation.domain.ReputationReason;
import com.campusoverflow.shared.audit.AuditTrail;
import com.campusoverflow.shared.event.IntegrationEvent;
import com.campusoverflow.shared.event.IntegrationEventPublisher;
import com.campusoverflow.shared.event.ProcessedEventStore;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Reputation 应用层的手写测试替身：只依赖端口，不起 Spring、不连库、不连 Redis。 */
public final class ReputationTestFixtures {

    private ReputationTestFixtures() {
    }

    public static final class Accounts implements ReputationAccountRepository {
        private final Map<Long, ReputationAccount> store = new LinkedHashMap<>();

        public void given(long userId, int reputation, int available, int frozen) {
            store.put(userId, ReputationAccount.reconstitute(userId, reputation, available, frozen, 0));
        }

        public ReputationAccount get(long userId) {
            return store.get(userId);
        }

        @Override
        public Optional<ReputationAccount> findByUserId(long userId) {
            return Optional.ofNullable(store.get(userId));
        }

        @Override
        public List<ReputationAccount> findAllByUserId(Collection<Long> userIds) {
            List<ReputationAccount> result = new ArrayList<>();
            userIds.forEach(id -> findByUserId(id).ifPresent(result::add));
            return result;
        }

        @Override
        public List<ReputationAccount> topByReputation(int limit) {
            return findAll().stream()
                    .sorted((a, b) -> Integer.compare(b.reputation(), a.reputation()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ReputationAccount> findAll() {
            return List.copyOf(store.values());
        }

        @Override
        public ReputationAccount save(ReputationAccount account) {
            store.put(account.userId(), account);
            return account;
        }
    }

    public static final class Ledger implements LedgerRepository {
        private final List<LedgerEntry> entries = new ArrayList<>();
        private final AtomicLong sequence = new AtomicLong();

        public List<LedgerEntry> entries() {
            return List.copyOf(entries);
        }

        @Override
        public boolean exists(String eventId, long userId, ReputationReason reason) {
            return entries.stream().anyMatch(e -> e.eventId().equals(eventId) && e.userId() == userId
                    && e.reason() == reason);
        }

        @Override
        public void append(LedgerEntry entry) {
            entries.add(new LedgerEntry(sequence.incrementAndGet(), entry.userId(), entry.eventId(), entry.reason(),
                    entry.delta(), entry.refType(), entry.refId(), entry.createdAt()));
        }

        @Override
        public PageResult<LedgerEntry> findByUser(long userId, PageRequest page) {
            List<LedgerEntry> mine = entries.stream().filter(e -> e.userId() == userId).toList();
            List<LedgerEntry> slice = mine.stream().skip(page.offset()).limit(page.size()).toList();
            return new PageResult<>(slice, mine.size(), page.page(), page.size());
        }

        @Override
        public long countByUserAndReason(long userId, ReputationReason reason) {
            return entries.stream().filter(e -> e.userId() == userId && e.reason() == reason).count();
        }

        @Override
        public int sumPositiveVoteDeltaSince(long userId, Instant since) {
            return entries.stream()
                    .filter(e -> e.userId() == userId && e.reason().isVote())
                    .filter(e -> !e.createdAt().isBefore(since))
                    .mapToInt(e -> Math.max(0, e.delta()))
                    .sum();
        }

        @Override
        public Map<Long, Long> sumByUser() {
            Map<Long, Long> sums = new LinkedHashMap<>();
            for (LedgerEntry e : entries) {
                sums.merge(e.userId(), (long) e.delta(), Long::sum);
            }
            return sums;
        }
    }

    public static final class Badges implements BadgeRepository {
        private final List<BadgeAward> awards = new ArrayList<>();

        public List<BadgeAward> awards() {
            return List.copyOf(awards);
        }

        @Override
        public boolean has(long userId, BadgeType type) {
            return awards.stream().anyMatch(a -> a.userId() == userId && a.type() == type);
        }

        @Override
        public void award(BadgeAward award) {
            awards.add(award);
        }

        @Override
        public List<BadgeAward> findByUser(long userId) {
            return awards.stream().filter(a -> a.userId() == userId).toList();
        }
    }

    public static final class Bounties implements BountyRepository {
        private final Map<Long, Bounty> store = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public Optional<Bounty> findOpenByQuestion(long questionId) {
            return store.values().stream()
                    .filter(b -> b.questionId() == questionId && b.status() == BountyStatus.OPEN)
                    .findFirst();
        }

        @Override
        public Optional<Bounty> findLatestByQuestion(long questionId) {
            return store.values().stream()
                    .filter(b -> b.questionId() == questionId)
                    .reduce((a, b) -> idOf(b) > idOf(a) ? b : a);
        }

        @Override
        public List<Bounty> findDue(Instant now, int limit) {
            return store.values().stream()
                    .filter(b -> b.status() == BountyStatus.OPEN && b.isDue(now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Bounty save(Bounty bounty) {
            if (bounty.id() == null) {
                bounty.assignId(sequence.incrementAndGet());
            }
            store.put(bounty.id(), bounty);
            return bounty;
        }

        private static long idOf(Bounty b) {
            return b.id() == null ? 0L : b.id();
        }
    }

    public static final class Identity implements IdentityApi {
        private final Map<Long, UserSummary> users = new LinkedHashMap<>();
        private final Set<Long> courseManagers = new LinkedHashSet<>();

        public void given(long id, String displayName, boolean verified) {
            users.put(id, new UserSummary(id, displayName, com.campusoverflow.shared.security.Role.STUDENT, verified,
                    "计算机工程学院"));
        }

        public void givenCourseManager(long userId) {
            courseManagers.add(userId);
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
            return courseManagers.contains(userId);
        }
    }

    public static final class Qa implements QaQueryApi {
        private final Map<Long, QuestionRef> questions = new LinkedHashMap<>();

        public void given(long id, long authorId, Long courseId, String title, Long acceptedAnswerId,
                          boolean deleted) {
            questions.put(id, new QuestionRef(id, authorId, courseId, title, acceptedAnswerId, deleted));
        }

        @Override
        public Optional<QuestionRef> findQuestion(long questionId) {
            return Optional.ofNullable(questions.get(questionId));
        }
    }

    public static final class Events implements IntegrationEventPublisher {
        private final List<IntegrationEvent> published = new ArrayList<>();

        public List<IntegrationEvent> published() {
            return List.copyOf(published);
        }

        public <T extends IntegrationEvent> List<T> of(Class<T> type) {
            return published.stream().filter(type::isInstance).map(type::cast).toList();
        }

        @Override
        public void publish(IntegrationEvent event) {
            published.add(event);
        }
    }

    public static final class Audit implements AuditTrail {
        private final List<String> records = new ArrayList<>();

        public List<String> records() {
            return List.copyOf(records);
        }

        @Override
        public void record(String action, Long actorId, String targetType, String targetId, String detail) {
            records.add(action + "|" + actorId + "|" + targetType + "|" + targetId);
        }
    }

    public static final class Processed implements ProcessedEventStore {
        private final Set<String> keys = new LinkedHashSet<>();

        @Override
        public boolean markProcessed(String eventId, String consumer) {
            return keys.add(consumer + "#" + eventId);
        }
    }
}
