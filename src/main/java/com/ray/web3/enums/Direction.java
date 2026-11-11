package com.ray.web3.enums;

import java.math.BigDecimal;

/**
 * 分录方向（第 2 周 U1-b：复式账本）
 * <p>
 * <b>符号约定与重建 SQL 严格一致</b>：{@link #CREDIT} = 余额增加，{@link #DEBIT} = 余额减少。
 * 重建余额时对某个账户做 {@code SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END)}
 * 必须等于 {@code account.available + account.frozen} 中对应子账的值。
 * <p>
 * 注意这与教科书会计对资产账户的借贷方向相反——本项目以“链上/账本金额流向”为准，
 * 改这里之前先改重建 SQL 与对账脚本，三处必须同口径。
 */
public enum Direction {

    /** 入账：子账余额增加 */
    CREDIT(1),

    /** 出账：子账余额减少 */
    DEBIT(-1);

    private final int sign;

    Direction(int sign) {
        this.sign = sign;
    }

    /** 在给定余额上应用本方向的分录，返回操作后的余额 */
    public BigDecimal apply(BigDecimal balance, BigDecimal amount) {
        return sign > 0 ? balance.add(amount) : balance.subtract(amount);
    }

    /** 本方向对净额贡献的带符号金额，用于借贷平衡校验 */
    public BigDecimal signed(BigDecimal amount) {
        return sign > 0 ? amount : amount.negate();
    }

    /** 反向分录：冲正时把每一条 posting 的加减对调 */
    public Direction opposite() {
        return sign > 0 ? DEBIT : CREDIT;
    }
}
