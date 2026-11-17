package com.campusoverflow.reputation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.reputation.application.fake.ReputationTestFixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReputationApiImplTest {

    private final ReputationTestFixtures.Accounts accounts = new ReputationTestFixtures.Accounts();
    private final ReputationApiImpl api = new ReputationApiImpl(accounts);

    @Test
    void summaryReturnsStoredReputationOrZero() {
        accounts.given(7, 15, 0, 0);

        assertThat(api.summary(7).reputation()).isEqualTo(15);
        assertThat(api.summary(7).userId()).isEqualTo(7);
        assertThat(api.summary(404).reputation()).isZero();
    }

    @Test
    void reputationsFillsZerosSoCallersNeedNoGetOrDefault() {
        accounts.given(7, 15, 0, 0);

        Map<Long, Integer> map = api.reputations(List.of(7L, 8L, 7L));

        assertThat(map).containsEntry(7L, 15).containsEntry(8L, 0).hasSize(2);
    }

    @Test
    void reputationsHandlesNullAndEmpty() {
        assertThat(api.reputations(null)).isEmpty();
        assertThat(api.reputations(List.of())).isEmpty();
    }

    @Test
    void reputationsIgnoresNullIds() {
        Map<Long, Integer> map = api.reputations(java.util.Arrays.asList(7L, null));

        assertThat(map).containsOnlyKeys(7L);
    }
}
