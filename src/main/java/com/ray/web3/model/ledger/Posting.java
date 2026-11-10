package com.ray.web3.model.ledger;

import com.ray.web3.enums.BalanceType;
import com.ray.web3.enums.Direction;
import com.ray.web3.util.MoneyUtils;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * 单条分录（第 2 周 U1-b）
 * <p>
 * 不可变值对象：账本一旦写入禁止修改，所有字段在构造时完成校验与精度归一化，
 * 下游拿到的金额一定满足 {@code DECIMAL(38,18)} 且为正数。
 * <p>
 * 方向在这里只表达“加/减”，不携带业务语义；业务语义由所属 {@link LedgerGroup} 的 bizType 决定。
 *
 * @param accountId   账户 ID，{@code account.id}
 * @param direction   分录方向，CREDIT=加、DEBIT=减
 * @param amount      金额，永远为正，构造时归一化到账本精度 18 位
 * @param currency    币种符号（构造时 trim + 转大写），同组内同币种必须借贷相等
 * @param balanceType 子账类型：动 available 还是 frozen
 */
public record Posting(
        long accountId,
        Direction direction,
        BigDecimal amount,
        String currency,
        BalanceType balanceType) {

    public Posting {
        if (accountId <= 0) {
            throw new IllegalArgumentException("accountId 必须为正数: " + accountId);
        }
        if (direction == null) {
            throw new IllegalArgumentException("分录方向不能为空");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("分录金额必须为正数: " + amount);
        }
        // 账本侧统一 18 位精度；截断后为 0 说明金额小于最小可记账单位，直接拒绝，
        // 否则会写出一条 amount=0 的分录，借款平衡看似成立却污染对账
        BigDecimal normalized = MoneyUtils.normalize(amount);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("分录金额小于账本最小精度(1e-18): " + amount.toPlainString());
        }
        amount = normalized;

        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("币种不能为空");
        }
        currency = currency.trim().toUpperCase(Locale.ROOT);

        if (balanceType == null) {
            throw new IllegalArgumentException("子账类型不能为空，可用值: AVAILABLE/FROZEN");
        }
    }
}
