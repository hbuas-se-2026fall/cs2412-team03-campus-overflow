package com.campusoverflow.reputation.application;

import com.campusoverflow.reputation.api.ReputationApi;
import com.campusoverflow.reputation.api.ReputationSummary;
import com.campusoverflow.reputation.domain.ReputationAccount;
import com.campusoverflow.reputation.domain.ReputationAccountRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReputationApiImpl implements ReputationApi {

    private final ReputationAccountRepository accounts;

    public ReputationApiImpl(ReputationAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public ReputationSummary summary(long userId) {
        int reputation = accounts.findByUserId(userId).map(ReputationAccount::reputation).orElse(0);
        return new ReputationSummary(userId, reputation);
    }

    /**
     * 批量查询声誉，避免排行榜之类的页面 N+1。
     * 没有账户的用户也保留在结果里、声誉记 0，调用方不必再写 {@code getOrDefault}。
     */
    @Override
    public Map<Long, Integer> reputations(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (Long id : new LinkedHashSet<>(userIds)) {
            if (id != null) {
                result.put(id, 0);
            }
        }
        accounts.findAllByUserId(result.keySet()).forEach(a -> result.put(a.userId(), a.reputation()));
        return Map.copyOf(result);
    }
}
