package com.ray.web3.ledger;

import com.ray.web3.consts.LedgerConsts;
import com.ray.web3.enums.BalanceType;
import com.ray.web3.enums.Direction;
import com.ray.web3.model.entity.LedgerEntry;
import com.ray.web3.model.entity.LedgerPosting;
import com.ray.web3.model.ledger.LedgerGroup;
import com.ray.web3.model.ledger.Posting;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 四类业务分录 + 冲正的单测（第 2 周 Day 5-6）
 * <p>
 * {@link LedgerGroupFactory} 是纯函数，这里逐条断言“方向 + 子账 + 金额”，
 * 不依赖数据库与事务。分录写错就是资损，必须在最便宜的层面锁死。
 */
class LedgerGroupFactoryTest {

    private static final String USDT = "USDT";

    @Test
    void 充值落账_用户可用增加_平台负债减少() {
        LedgerGroup group = LedgerGroupFactory.deposit(
                "DEP-1", 11L, 99L, new BigDecimal("100"), USDT);

        assertThat(group.bizType()).isEqualTo(LedgerConsts.BizType.DEPOSIT);
        assertThat(group.bizNo()).isEqualTo("DEP-1");
        assertThat(group.reverseOf()).isNull();
        assertThat(group.postings()).hasSize(2);

        Posting userLeg = group.postings().get(0);
        assertThat(userLeg.accountId()).isEqualTo(11L);
        assertThat(userLeg.direction()).isEqualTo(Direction.CREDIT);
        assertThat(userLeg.balanceType()).isEqualTo(BalanceType.AVAILABLE);
        assertThat(userLeg.amount()).isEqualByComparingTo("100");

        Posting liabilityLeg = group.postings().get(1);
        assertThat(liabilityLeg.accountId()).isEqualTo(99L);
        assertThat(liabilityLeg.direction()).isEqualTo(Direction.DEBIT);
        assertThat(liabilityLeg.balanceType()).isEqualTo(BalanceType.AVAILABLE);

        assertBalanced(group);
    }

    @Test
    void 下单冻结_可用转冻结_总额不变() {
        LedgerGroup group = LedgerGroupFactory.freezeOrder("ORD-1", 11L, new BigDecimal("30"), USDT);

        assertThat(group.bizType()).isEqualTo(LedgerConsts.BizType.FREEZE);
        assertThat(group.bizNo()).isEqualTo("ORD-1");
        assertThat(group.postings()).hasSize(2);

        Posting fromAvailable = group.postings().get(0);
        assertThat(fromAvailable.accountId()).isEqualTo(11L);
        assertThat(fromAvailable.direction()).isEqualTo(Direction.DEBIT);
        assertThat(fromAvailable.balanceType()).isEqualTo(BalanceType.AVAILABLE);

        Posting toFrozen = group.postings().get(1);
        assertThat(toFrozen.accountId()).isEqualTo(11L);
        assertThat(toFrozen.direction()).isEqualTo(Direction.CREDIT);
        assertThat(toFrozen.balanceType()).isEqualTo(BalanceType.FROZEN);

        // 同一账户、同一金额：available -30 / frozen +30，total 不变
        assertBalanced(group);
    }

    @Test
    void 成交清算_本金与手续费各一组且分别平账() {
        LedgerGroup principal = LedgerGroupFactory.settleTrade(
                "TRADE-1", 11L, 22L, new BigDecimal("100"), USDT);
        LedgerGroup fee = LedgerGroupFactory.tradeFee(
                "TRADE-1", 11L, 88L, new BigDecimal("1"), USDT);

        assertThat(principal.bizType()).isEqualTo(LedgerConsts.BizType.TRANSFER);
        assertThat(fee.bizType()).isEqualTo(LedgerConsts.BizType.FEE);
        // 不同 bizType 可以复用同一个 tradeNo，各自幂等
        assertThat(fee.bizNo()).isEqualTo(principal.bizNo());

        assertThat(principal.postings().get(0).accountId()).isEqualTo(11L);
        assertThat(principal.postings().get(0).direction()).isEqualTo(Direction.DEBIT);
        assertThat(principal.postings().get(0).balanceType()).isEqualTo(BalanceType.FROZEN);
        assertThat(principal.postings().get(1).accountId()).isEqualTo(22L);
        assertThat(principal.postings().get(1).direction()).isEqualTo(Direction.CREDIT);
        assertThat(principal.postings().get(1).balanceType()).isEqualTo(BalanceType.AVAILABLE);

        assertThat(fee.postings().get(0).direction()).isEqualTo(Direction.DEBIT);
        assertThat(fee.postings().get(0).balanceType()).isEqualTo(BalanceType.AVAILABLE);
        assertThat(fee.postings().get(1).accountId()).isEqualTo(88L);
        assertThat(fee.postings().get(1).direction()).isEqualTo(Direction.CREDIT);

        assertBalanced(principal);
        assertBalanced(fee);
    }

    @Test
    void 提币失败解冻_冻结转回可用_按原提币单号幂等() {
        LedgerGroup group = LedgerGroupFactory.unfreezeWithdraw(
                "WD-1", 11L, new BigDecimal("30"), USDT);

        assertThat(group.bizType()).isEqualTo(LedgerConsts.BizType.UNFREEZE);
        assertThat(group.bizNo()).isEqualTo("WD-1");

        Posting fromFrozen = group.postings().get(0);
        assertThat(fromFrozen.direction()).isEqualTo(Direction.DEBIT);
        assertThat(fromFrozen.balanceType()).isEqualTo(BalanceType.FROZEN);
        assertThat(group.postings().get(1).direction()).isEqualTo(Direction.CREDIT);
        assertThat(group.postings().get(1).balanceType()).isEqualTo(BalanceType.AVAILABLE);

        assertBalanced(group);
    }

    @Test
    void 冲正_方向全部对调_reverseOf指向原分录() {
        LedgerEntry original = new LedgerEntry();
        original.setId(7L);
        original.setEntryNo("LE202611110001");
        original.setBizType(LedgerConsts.BizType.FREEZE);
        original.setBizNo("ORD-1");

        List<LedgerPosting> rows = List.of(
                postingRow(11L, "DEBIT", "30", "AVAILABLE"),
                postingRow(11L, "CREDIT", "30", "FROZEN"));

        LedgerGroup reversal = LedgerGroupFactory.reversal(original, rows);

        assertThat(reversal.bizType()).isEqualTo(LedgerConsts.BizType.REVERSE);
        assertThat(reversal.bizNo()).isEqualTo("REV:LE202611110001");
        assertThat(reversal.reverseOf()).isEqualTo(7L);

        assertThat(reversal.postings()).hasSize(2);
        assertThat(reversal.postings().get(0).direction()).isEqualTo(Direction.CREDIT);
        assertThat(reversal.postings().get(0).balanceType()).isEqualTo(BalanceType.AVAILABLE);
        assertThat(reversal.postings().get(1).direction()).isEqualTo(Direction.DEBIT);
        assertThat(reversal.postings().get(1).balanceType()).isEqualTo(BalanceType.FROZEN);

        assertBalanced(reversal);
    }

    @Test
    void 冲正_原分录无明细应拒绝() {
        LedgerEntry original = new LedgerEntry();
        original.setId(7L);
        original.setEntryNo("LE202611110002");

        assertThatThrownBy(() -> LedgerGroupFactory.reversal(original, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("明细为空");
    }

    /** 按币种校验 sum(CREDIT) - sum(DEBIT) == 0 */
    private static void assertBalanced(LedgerGroup group) {
        BigDecimal net = group.postings().stream()
                .map(p -> p.direction().signed(p.amount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net.signum()).as("分录组必须借贷相等: %s", group.postings()).isZero();
    }

    private static LedgerPosting postingRow(long accountId, String direction,
                                            String amount, String balanceType) {
        LedgerPosting row = new LedgerPosting();
        row.setAccountId(accountId);
        row.setDirection(direction);
        row.setAmount(new BigDecimal(amount));
        row.setCurrency(USDT);
        row.setBalanceType(balanceType);
        return row;
    }
}
