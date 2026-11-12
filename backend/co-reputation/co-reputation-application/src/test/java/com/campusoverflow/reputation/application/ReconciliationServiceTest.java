package com.campusoverflow.reputation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.reputation.domain.LedgerEntry;
import com.campusoverflow.reputation.domain.LedgerRepository;
import com.campusoverflow.reputation.domain.ReputationAccount;
import com.campusoverflow.reputation.domain.ReputationAccountRepository;
import com.campusoverflow.reputation.domain.ReputationReason;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** FF-8 对账：余额 = 流水之和。对账本身写错，比不写更糟（会给出错误的“一致”结论）。 */
class ReconciliationServiceTest {

    @Test
    void reportsAccountWhoseBalanceDiffersFromLedgerSum() {
        var accounts = new FakeAccounts(Map.of(1L, 15, 2L, 100));
        var ledger = new FakeLedger(Map.of(1L, 15L, 2L, 90L));

        List<ReconciliationService.Mismatch> mismatches = new ReconciliationService(accounts, ledger).reconcile();

        assertThat(mismatches).containsExactly(new ReconciliationService.Mismatch(2L, 100, 90L));
    }

    @Test
    void reportsLedgerEntriesWithoutAccount() {
        var accounts = new FakeAccounts(Map.of(1L, 15));
        var ledger = new FakeLedger(Map.of(1L, 15L, 9L, 40L));

        List<ReconciliationService.Mismatch> mismatches = new ReconciliationService(accounts, ledger).reconcile();

        assertThat(mismatches).containsExactly(new ReconciliationService.Mismatch(9L, 0, 40L));
    }

    @Test
    void returnsEmptyWhenBalanceMatchesEverywhere() {
        var accounts = new FakeAccounts(Map.of(1L, 15, 2L, 0));
        var ledger = new FakeLedger(Map.of(1L, 15L, 2L, 0L));

        assertThat(new ReconciliationService(accounts, ledger).reconcile()).isEmpty();
    }

    @Test
    void resultIsSortedByUserIdSoReportsAreComparableBetweenRounds() {
        var accounts = new FakeAccounts(new LinkedHashMap<>(Map.of(5L, 1, 1L, 2, 3L, 3)));
        var ledger = new FakeLedger(Map.of());

        List<ReconciliationService.Mismatch> mismatches = new ReconciliationService(accounts, ledger).reconcile();

        assertThat(mismatches).extracting(ReconciliationService.Mismatch::userId).containsExactly(1L, 3L, 5L);
    }

    // ---------------- fakes ----------------

    private static final class FakeAccounts implements ReputationAccountRepository {
        private final Map<Long, Integer> reputations;

        FakeAccounts(Map<Long, Integer> reputations) {
            this.reputations = reputations;
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

    private static final class FakeLedger implements LedgerRepository {
        private final Map<Long, Long> sums;

        FakeLedger(Map<Long, Long> sums) {
            this.sums = sums;
        }

        @Override
        public boolean exists(String eventId, long userId, ReputationReason reason) {
            return false;
        }

        @Override
        public void append(LedgerEntry entry) {
            sums.merge(entry.userId(), (long) entry.delta(), Long::sum);
        }

        @Override
        public PageResult<LedgerEntry> findByUser(long userId, PageRequest page) {
            return PageResult.empty(page.page(), page.size());
        }

        @Override
        public long countByUserAndReason(long userId, ReputationReason reason) {
            return 0;
        }

        @Override
        public int sumPositiveVoteDeltaSince(long userId, Instant since) {
            return 0;
        }

        @Override
        public Map<Long, Long> sumByUser() {
            return Map.copyOf(sums);
        }
    }
}
