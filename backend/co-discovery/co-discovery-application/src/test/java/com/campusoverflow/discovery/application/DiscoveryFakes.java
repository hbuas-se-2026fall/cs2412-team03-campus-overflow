package com.campusoverflow.discovery.application;

import com.campusoverflow.discovery.domain.notification.Notification;
import com.campusoverflow.discovery.domain.notification.NotificationRepository;
import com.campusoverflow.discovery.domain.search.SearchDocument;
import com.campusoverflow.discovery.domain.search.SearchIndex;
import com.campusoverflow.identity.api.CourseSummary;
import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.shared.event.ProcessedEventStore;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 应用层单元测试用的端口替身：只记录调用，不引入 Spring 上下文与数据库。 */
final class DiscoveryFakes {

    private DiscoveryFakes() {
    }

    static final class Identities implements IdentityApi {

        final Map<Long, UserSummary> byId = new LinkedHashMap<>();
        final Map<String, UserSummary> byName = new LinkedHashMap<>();
        final List<List<Long>> batchQueries = new ArrayList<>();

        Identities add(long id, String displayName) {
            UserSummary u = new UserSummary(id, displayName, Role.STUDENT, true, "计算机工程学院");
            byId.put(id, u);
            byName.put(displayName, u);
            return this;
        }

        @Override
        public Optional<UserSummary> findUser(long userId) {
            return Optional.ofNullable(byId.get(userId));
        }

        @Override
        public Map<Long, UserSummary> findUsers(Collection<Long> userIds) {
            List<Long> ids = List.copyOf(userIds);
            batchQueries.add(ids);
            Map<Long, UserSummary> result = new LinkedHashMap<>();
            for (Long id : ids) {
                UserSummary u = byId.get(id);
                if (u != null) {
                    result.put(id, u);
                }
            }
            return result;
        }

        @Override
        public Optional<UserSummary> findByDisplayName(String displayName) {
            return Optional.ofNullable(byName.get(displayName));
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

    static final class Notifications implements NotificationRepository {

        final List<Notification> saved = new ArrayList<>();
        final Set<String> keys = new HashSet<>();
        private long nextId = 1;

        @Override
        public boolean exists(String sourceEventId, long recipientId) {
            return keys.contains(key(sourceEventId, recipientId));
        }

        @Override
        public Notification save(Notification notification) {
            if (notification.id() == null) {
                notification.assignId(nextId++);
                keys.add(key(notification.sourceEventId(), notification.recipientId()));
            }
            if (saved.stream().noneMatch(n -> n.id().equals(notification.id()))) {
                saved.add(notification);
            }
            return notification;
        }

        @Override
        public Optional<Notification> findById(long id) {
            return saved.stream().filter(n -> n.id() != null && n.id() == id).findFirst();
        }

        @Override
        public PageResult<Notification> findByRecipient(long recipientId, PageRequest page) {
            List<Notification> mine = saved.stream().filter(n -> n.recipientId() == recipientId).toList();
            long total = mine.size();
            List<Notification> items = mine.stream().skip(page.offset()).limit(page.size()).toList();
            return new PageResult<>(items, total, page.page(), page.size());
        }

        @Override
        public long countUnread(long recipientId) {
            return saved.stream().filter(n -> n.recipientId() == recipientId && !n.read()).count();
        }

        @Override
        public int markAllRead(long recipientId) {
            List<Notification> mine = saved.stream()
                    .filter(n -> n.recipientId() == recipientId && !n.read()).toList();
            mine.forEach(n -> n.markRead(recipientId));
            return mine.size();
        }

        private static String key(String eventId, long recipientId) {
            return eventId + "#" + recipientId;
        }
    }

    static final class SearchIndexes implements SearchIndex {

        final List<String> calls = new ArrayList<>();
        List<String> tags = List.of();
        List<RelatedCandidate> candidates = List.of();
        List<String> requestedTags = List.of();
        int requestedLimit;

        @Override
        public void insert(SearchDocument document) {
            calls.add("insert:" + document.questionId() + ":" + document.tags().size());
        }

        @Override
        public void updateText(long questionId, String title, String bodyText, List<String> newTags, Instant at) {
            calls.add("updateText:" + questionId + ":" + newTags.size());
        }

        @Override
        public void adjustAnswerCount(long questionId, int delta, Instant at) {
            calls.add("answerCount:" + questionId + ":" + delta);
        }

        @Override
        public void adjustScore(long questionId, int delta) {
            calls.add("score:" + questionId + ":" + delta);
        }

        @Override
        public void markAccepted(long questionId, Instant at) {
            calls.add("accepted:" + questionId);
        }

        @Override
        public void setBountyOpen(long questionId, boolean open) {
            calls.add("bounty:" + questionId + ":" + open);
        }

        @Override
        public void markDeleted(long questionId) {
            calls.add("deleted:" + questionId);
        }

        @Override
        public List<String> findTags(long questionId) {
            return tags;
        }

        @Override
        public List<RelatedCandidate> findCandidatesSharingTags(long questionId, List<String> queryTags, int limit) {
            requestedTags = queryTags;
            requestedLimit = limit;
            return candidates;
        }
    }

    static final class Processed implements ProcessedEventStore {

        final Set<String> seen = new HashSet<>();
        final List<String> consumers = new ArrayList<>();

        @Override
        public boolean markProcessed(String eventId, String consumer) {
            consumers.add(consumer);
            return seen.add(consumer + "#" + eventId);
        }
    }
}
