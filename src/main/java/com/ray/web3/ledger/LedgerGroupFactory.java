package com.ray.web3.ledger;

import com.ray.web3.consts.LedgerConsts;
import com.ray.web3.enums.BalanceType;
import com.ray.web3.enums.Direction;
import com.ray.web3.model.entity.LedgerEntry;
import com.ray.web3.model.entity.LedgerPosting;
import com.ray.web3.model.ledger.LedgerGroup;
import com.ray.web3.model.ledger.Posting;

import java.math.BigDecimal;
import java.util.List;

/**
 * 四类业务场景的分录构造器（第 2 周 Day 5-6）
 * <p>
 * 刻意与 {@code LedgerService} 分离：这里只负责“业务动作 → 借贷分录”的翻译，是纯函数，
 * 不碰数据库、不碰事务，单测可以逐条断言方向与金额；Service 负责落库与余额校验。
 * 分录写错是资损级 bug，把它变成可枚举、可断言的数据结构，比散落在业务流程里安全得多。
 * <p>
 * 符号约定：CREDIT = 余额增加，DEBIT = 余额减少（见 {@link Direction}）。
 * <p>
 * 四个场景：
 * <ol>
 *   <li>充值落账：用户 available ↑ / 平台代收负债 ↓</li>
 *   <li>下单冻结：用户 available ↓ / 用户 frozen ↑（总额不变）</li>
 *   <li>成交清算：Taker frozen ↓ / Maker available ↑；手续费单独一组 FEE</li>
 *   <li>提币失败解冻：用户 frozen ↓ / 用户 available ↑（②的逆向）</li>
 * </ol>
 * 冲正：把原组每条 posting 的方向对调，reverse_of 指向原 entry。
 */
public final class LedgerGroupFactory {

    /** 冲正单号前缀：用原 entry_no（全局唯一、≤64 字符）而不是业务单号，避免超长与重复 */
    private static final String REVERSAL_NO_PREFIX = "REV:";

    private LedgerGroupFactory() {
    }

    /**
     * ① 充值落账：链上到账确认后调用
     * <p>
     * {@code 用户 SPOT.available +amount / 平台代收负债 -amount}。
     * 平台负债是 {@code account} 表里 {@code account_type='PLATFORM'} 的保留账户
     * （该类型允许负余额，否则“平台负债减少”会被余额校验拦下），
     * 金额取链上到账额（{@code MoneyUtils.toDisplay(raw, decimals)}）。
     * <b>确认数不足不能落账</b>：调用方必须等 deposit_record.status=CREDITED 后再调本方法。
     */
    public static LedgerGroup deposit(String depositNo,
                                      long userAccountId,
                                      long platformLiabilityAccountId,
                                      BigDecimal amount,
                                      String currency) {
        return new LedgerGroup(LedgerConsts.BizType.DEPOSIT, depositNo, List.of(
                new Posting(userAccountId, Direction.CREDIT, amount, currency, BalanceType.AVAILABLE),
                new Posting(platformLiabilityAccountId, Direction.DEBIT, amount, currency, BalanceType.AVAILABLE)));
    }

    /**
     * ② 下单冻结：可用 → 冻结，{@code total = available + frozen} 不变。
     * <p>
     * 两条 posting 落在同一个 account_id 的两个子账上，组内借贷仍然相等。
     */
    public static LedgerGroup freezeOrder(String orderNo,
                                          long userAccountId,
                                          BigDecimal amount,
                                          String currency) {
        return new LedgerGroup(LedgerConsts.BizType.FREEZE, orderNo, List.of(
                new Posting(userAccountId, Direction.DEBIT, amount, currency, BalanceType.AVAILABLE),
                new Posting(userAccountId, Direction.CREDIT, amount, currency, BalanceType.FROZEN)));
    }

    /**
     * ③-a 成交本金：Taker 冻结扣减，Maker 可用增加。
     * <p>
     * 单腿（计价币）简化：真实撮合还有资产腿，按同一 tradeNo 另记一组业务类型为 TRANSFER 的分录，
     * 不能用同一个 bizType 拼进本组——uk_biz 只认 (biz_type, biz_no)，同类型同单号只能有一组。
     */
    public static LedgerGroup settleTrade(String tradeNo,
                                          long takerAccountId,
                                          long makerAccountId,
                                          BigDecimal amount,
                                          String currency) {
        return new LedgerGroup(LedgerConsts.BizType.TRANSFER, tradeNo, List.of(
                new Posting(takerAccountId, Direction.DEBIT, amount, currency, BalanceType.FROZEN),
                new Posting(makerAccountId, Direction.CREDIT, amount, currency, BalanceType.AVAILABLE)));
    }

    /**
     * ③-b 手续费：单独一组 FEE，与本金分开记。
     * <p>
     * 分开记的原因：本金是用户之间的对赌，手续费是平台收入；混在一组会导致
     * “平台收入”无法单独统计，冲正时也无法只退本金不退手续费。
     * bizType=FEE 与本金 TRANSFER 不同，因此可以复用同一个 tradeNo。
     */
    public static LedgerGroup tradeFee(String tradeNo,
                                       long payerAccountId,
                                       long platformFeeAccountId,
                                       BigDecimal fee,
                                       String currency) {
        return new LedgerGroup(LedgerConsts.BizType.FEE, tradeNo, List.of(
                new Posting(payerAccountId, Direction.DEBIT, fee, currency, BalanceType.AVAILABLE),
                new Posting(platformFeeAccountId, Direction.CREDIT, fee, currency, BalanceType.AVAILABLE)));
    }

    /**
     * ④ 提币失败解冻：与②方向相反，biz_no 用原提币单号。
     * <p>
     * 用独立的 {@code UNFREEZE} 业务类型而不是复用 FREEZE：同一个提币单号既冻结又解冻，
     * 若共用 bizType 会撞 {@code uk_biz(biz_type, biz_no)}，重复调用反而报唯一键冲突。
     * (UNFREEZE, withdrawNo) 天然幂等：重复解冻只会返回首次解冻的分录组。
     */
    public static LedgerGroup unfreezeWithdraw(String withdrawNo,
                                               long userAccountId,
                                               BigDecimal amount,
                                               String currency) {
        return new LedgerGroup(LedgerConsts.BizType.UNFREEZE, withdrawNo, List.of(
                new Posting(userAccountId, Direction.DEBIT, amount, currency, BalanceType.FROZEN),
                new Posting(userAccountId, Direction.CREDIT, amount, currency, BalanceType.AVAILABLE)));
    }

    /**
     * 冲正：原组每条 posting 方向对调，金额不变；reverse_of 指向原 entry。
     * <p>
     * 原分录取向不变、不修改、不删除；冲正是一个新的分录组，靠 reverse_of 关联。
     * 冲正单号用原 entry_no（{@code REV:LE...}），保证同一原分录只会被冲正一次（uk_biz + uk_reverse_of 双保险）。
     */
    public static LedgerGroup reversal(LedgerEntry original, List<LedgerPosting> originalPostings) {
        if (original == null) {
            throw new IllegalArgumentException("原分录不能为空");
        }
        if (originalPostings == null || originalPostings.isEmpty()) {
            throw new IllegalArgumentException("原分录明细为空，无法冲正: entryNo=" + original.getEntryNo());
        }
        List<Posting> reversed = originalPostings.stream()
                .map(p -> new Posting(
                        p.getAccountId(),
                        Direction.valueOf(p.getDirection()).opposite(),
                        p.getAmount(),
                        p.getCurrency(),
                        BalanceType.valueOf(p.getBalanceType())))
                .toList();
        return new LedgerGroup(LedgerConsts.BizType.REVERSE,
                REVERSAL_NO_PREFIX + original.getEntryNo(),
                reversed,
                original.getId());
    }
}
