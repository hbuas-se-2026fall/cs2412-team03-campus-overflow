package com.campusoverflow.reputation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

/**
 * 声誉账户：声誉累加 + 悬赏积分的「可用 / 冻结」两账本。
 * 之前只有 29/37 行被覆盖，冻结-扣减-退还这条守恒链一条断言都没有。
 */
class ReputationAccountTest {

    @Test
    void 开户时声誉归零且初始积分不低于零() {
        ReputationAccount opened = ReputationAccount.open(9L, 1000);
        ReputationAccount noPoints = ReputationAccount.open(10L, 0);
        ReputationAccount negative = ReputationAccount.open(11L, -50);

        assertThat(opened.userId()).isEqualTo(9L);
        assertThat(opened.reputation()).isZero();
        assertThat(opened.availablePoints()).isEqualTo(1000);
        assertThat(opened.frozenPoints()).isZero();
        assertThat(opened.version()).isZero();
        assertThat(noPoints.availablePoints()).isZero();
        assertThat(negative.availablePoints()).isZero();
    }

    @Test
    void 声誉可以累加也可以扣减() {
        ReputationAccount account = ReputationAccount.open(9L, 0);

        account.applyReputation(15);
        account.applyReputation(-2);

        assertThat(account.reputation()).isEqualTo(13);
    }

    @Test
    void 冻结积分把可用余额搬到冻结余额() {
        ReputationAccount account = ReputationAccount.open(9L, 200);

        account.freezePoints(50);

        assertThat(account.availablePoints()).isEqualTo(150);
        assertThat(account.frozenPoints()).isEqualTo(50);
    }

    @Test
    void 可用积分不足时拒绝冻结() {
        ReputationAccount account = ReputationAccount.open(9L, 30);

        assertThatThrownBy(() -> account.freezePoints(50))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("可用悬赏积分不足")
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("REP-1002");
        assertThat(account.availablePoints()).isEqualTo(30);
        assertThat(account.frozenPoints()).isZero();
    }

    @Test
    void 非正数积分一律拒绝() {
        ReputationAccount account = ReputationAccount.open(9L, 200);

        assertThatThrownBy(() -> account.freezePoints(0))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("REP-1007");
        assertThatThrownBy(() -> account.spendFrozen(-10))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("REP-1007");
        assertThatThrownBy(() -> account.refundFrozen(0))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("REP-1007");
    }

    @Test
    void 结算冻结积分后冻结余额减少而可用余额不变() {
        ReputationAccount account = ReputationAccount.open(9L, 200);
        account.freezePoints(80);

        account.spendFrozen(80);

        assertThat(account.frozenPoints()).isZero();
        assertThat(account.availablePoints()).isEqualTo(120);
    }

    @Test
    void 退还冻结积分后回到可用余额() {
        ReputationAccount account = ReputationAccount.open(9L, 200);
        account.freezePoints(80);

        account.refundFrozen(80);

        assertThat(account.frozenPoints()).isZero();
        assertThat(account.availablePoints()).isEqualTo(200);
    }

    @Test
    void 冻结余额不足时结算与退还都拒绝() {
        ReputationAccount account = ReputationAccount.open(9L, 200);
        account.freezePoints(20);

        assertThatThrownBy(() -> account.spendFrozen(50))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("冻结积分不足")
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("REP-1006");
        assertThatThrownBy(() -> account.refundFrozen(50))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("REP-1006");
        assertThat(account.frozenPoints()).isEqualTo(20);
    }

    @Test
    void 冻结扣减退还三步之后总积分守恒() {
        ReputationAccount account = ReputationAccount.open(9L, 500);

        account.freezePoints(200);
        account.spendFrozen(120);   // 120 给回答者
        account.refundFrozen(80);   // 80 退回发起人

        assertThat(account.frozenPoints()).isZero();
        assertThat(account.availablePoints()).isEqualTo(380);
        assertThat(account.availablePoints() + account.frozenPoints()).isEqualTo(380);
    }

    @Test
    void 重建账户保留全部字段与版本号() {
        ReputationAccount account = ReputationAccount.reconstitute(9L, 130, 120, 80, 7);

        assertThat(account.userId()).isEqualTo(9L);
        assertThat(account.reputation()).isEqualTo(130);
        assertThat(account.availablePoints()).isEqualTo(120);
        assertThat(account.frozenPoints()).isEqualTo(80);
        assertThat(account.version()).isEqualTo(7);
    }
}
