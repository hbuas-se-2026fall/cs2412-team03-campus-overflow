package com.campusoverflow.identity.application.fake;

import com.campusoverflow.identity.domain.Course;
import com.campusoverflow.identity.domain.CourseMember;
import com.campusoverflow.identity.domain.CourseRepository;
import com.campusoverflow.identity.domain.CourseRole;
import com.campusoverflow.identity.domain.PasswordHasher;
import com.campusoverflow.identity.domain.User;
import com.campusoverflow.identity.domain.UserRepository;
import com.campusoverflow.identity.domain.UserStatus;
import com.campusoverflow.shared.audit.AuditTrail;
import com.campusoverflow.shared.event.IntegrationEvent;
import com.campusoverflow.shared.event.IntegrationEventPublisher;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Identity 应用层的手写测试替身：只实现端口，不起 Spring、不连库、不连 Redis。
 * <p>
 * co-identity-application 有 14 个类、此前 0 个测试——注册/登录/课程/管理员这些用例编排
 * 完全靠接口层联调才发现问题。这里补齐后，用例逻辑可以毫秒级验证。
 */
public final class IdentityTestFixtures {

    public static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private IdentityTestFixtures() {
    }

    public static Actor admin(long id) {
        return new Actor(id, "管理员" + id, Role.ADMIN);
    }

    public static Actor teacher(long id) {
        return new Actor(id, "教师" + id, Role.TEACHER);
    }

    public static Actor student(long id) {
        return new Actor(id, "学生" + id, Role.STUDENT);
    }

    /** 用户仓储替身：主键自增、唯一性检查与真实实现同口径（邮箱忽略大小写）。 */
    public static final class Users implements UserRepository {

        private final Map<Long, User> store = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong();

        public User given(long id, String username, String displayName, String email, Role role, boolean verified) {
            User user = User.reconstitute(id, username, displayName, email, "hash:" + username, role,
                    UserStatus.ACTIVE, verified, "计算机工程学院", "软件工程2024级", NOW, 0);
            store.put(id, user);
            sequence.set(Math.max(sequence.get(), id));
            return user;
        }

        public User givenStudent(long id, String username, String displayName, String email) {
            return given(id, username, displayName, email, Role.STUDENT, false);
        }

        public User get(long id) {
            return store.get(id);
        }

        @Override
        public Optional<User> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<User> findByUsername(String username) {
            return store.values().stream().filter(u -> u.username().equals(username)).findFirst();
        }

        @Override
        public Optional<User> findByDisplayName(String displayName) {
            return store.values().stream().filter(u -> u.displayName().equals(displayName)).findFirst();
        }

        @Override
        public boolean existsByUsername(String username) {
            return findByUsername(username).isPresent();
        }

        @Override
        public boolean existsByDisplayName(String displayName) {
            return findByDisplayName(displayName).isPresent();
        }

        @Override
        public boolean existsByEmail(String email) {
            return store.values().stream().anyMatch(u -> u.email().equalsIgnoreCase(email));
        }

        @Override
        public List<User> findAllById(Collection<Long> ids) {
            List<User> result = new ArrayList<>();
            ids.stream().distinct().map(store::get).filter(Objects::nonNull).forEach(result::add);
            return result;
        }

        @Override
        public PageResult<User> findByVerified(boolean verified, PageRequest page) {
            List<User> all = store.values().stream().filter(u -> u.verified() == verified).toList();
            List<User> slice = all.stream().skip(page.offset()).limit(page.size()).toList();
            return new PageResult<>(slice, all.size(), page.page(), page.size());
        }

        @Override
        public User save(User user) {
            if (user.id() == null) {
                user.assignId(sequence.incrementAndGet());
            }
            store.put(user.id(), user);
            return user;
        }
    }

    /** 课程仓储替身：课程与成员关系分两张表存，和 JPA 侧一致。 */
    public static final class Courses implements CourseRepository {

        private final Map<Long, Course> store = new LinkedHashMap<>();
        private final Map<String, CourseMember> members = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong();

        public Course given(long id, String code, String name, String term, long teacherId) {
            Course course = Course.reconstitute(id, code, name, term, teacherId, NOW);
            store.put(id, course);
            sequence.set(Math.max(sequence.get(), id));
            return course;
        }

        public CourseMember givenMember(long courseId, long userId, CourseRole role) {
            CourseMember member = CourseMember.reconstitute(courseId, userId, role, NOW);
            members.put(key(courseId, userId), member);
            return member;
        }

        public CourseMember member(long courseId, long userId) {
            return members.get(key(courseId, userId));
        }

        public Course course(long id) {
            return store.get(id);
        }

        @Override
        public Course save(Course course) {
            if (course.id() == null) {
                course.assignId(sequence.incrementAndGet());
            }
            store.put(course.id(), course);
            return course;
        }

        @Override
        public Optional<Course> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<Course> findAll() {
            return List.copyOf(store.values());
        }

        @Override
        public boolean existsByCodeAndTerm(String code, String term) {
            return store.values().stream().anyMatch(c -> c.code().equals(code) && c.term().equals(term));
        }

        @Override
        public Optional<CourseMember> findMember(long courseId, long userId) {
            return Optional.ofNullable(members.get(key(courseId, userId)));
        }

        @Override
        public CourseMember saveMember(CourseMember member) {
            members.put(key(member.courseId(), member.userId()), member);
            return member;
        }

        @Override
        public List<CourseMember> findMembers(long courseId) {
            return members.values().stream().filter(m -> m.courseId() == courseId).toList();
        }

        @Override
        public List<CourseMember> findMembershipsOfUser(long userId) {
            return members.values().stream().filter(m -> m.userId() == userId).toList();
        }

        private static String key(long courseId, long userId) {
            return courseId + ":" + userId;
        }
    }

    /** 密码哈希替身：可逆前缀，便于断言「存的是哈希不是明文」。 */
    public static final class Hasher implements PasswordHasher {

        @Override
        public String hash(String raw) {
            return "hashed:" + raw;
        }

        @Override
        public boolean matches(String raw, String hash) {
            return hash("hashed:" + raw).equals(hash) || ("hashed:" + raw).equals(hash);
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

    /** 审计替身：把五个参数压成一行，断言时直接比对字符串。 */
    public static final class Audit implements AuditTrail {

        private final List<String> records = new ArrayList<>();

        public List<String> records() {
            return List.copyOf(records);
        }

        public String last() {
            return records.isEmpty() ? null : records.get(records.size() - 1);
        }

        @Override
        public void record(String action, Long actorId, String targetType, String targetId, String detail) {
            records.add(action + "|" + actorId + "|" + targetType + "|" + targetId + "|" + detail);
        }
    }
}
