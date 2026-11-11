package com.campusoverflow.reputation.application;

import com.campusoverflow.reputation.domain.LedgerRepository;
import com.campusoverflow.reputation.domain.ReputationAccount;
import com.campusoverflow.reputation.domain.ReputationAccountRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 适应度函数 FF-8：对账——每个账户的声誉必须等于其流水之和。 */
@Service
public class ReconciliationService {

    public record Mismatch(long userId, int accountReputation, long ledgerSum) {
    }

    private final ReputationAccountRepository accounts;
    private final LedgerRepository ledger;

    public ReconciliationService(ReputationAccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /**
     * 双向对账，两个方向都要查，否则会漏掉另一半错误：
     * <ol>
     *   <li>账户余额 ≠ 该用户流水之和（余额被绕过账本改过，或流水丢了一条）；</li>
     *   <li>有流水但没有账户（开户事件丢失）；</li>
     * </ol>
     * 结果按 userId 升序返回，便于对账报告在多轮之间比对。
     */
    @Transactional(readOnly = true)
    public List<Mismatch> reconcile() {
        Map<Long, Long> sums = ledger.sumByUser();
        List<Mismatch> mismatches = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (ReputationAccount account : accounts.findAll()) {
            seen.add(account.userId());
            long sum = sums.getOrDefault(account.userId(), 0L);
            if (sum != account.reputation()) {
                mismatches.add(new Mismatch(account.userId(), account.reputation(), sum));
            }
        }

        for (Map.Entry<Long, Long> entry : sums.entrySet()) {
            if (!seen.contains(entry.getKey()) && entry.getValue() != 0L) {
                mismatches.add(new Mismatch(entry.getKey(), 0, entry.getValue()));
            }
        }

        mismatches.sort((a, b) -> Long.compare(a.userId(), b.userId()));
        return mismatches;
    }
}
