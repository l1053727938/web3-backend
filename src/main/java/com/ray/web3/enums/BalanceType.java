package com.ray.web3.enums;

import com.ray.web3.consts.LedgerConsts;

/**
 * 子账类型（第 2 周 U1-b：复式账本）
 * <p>
 * {@code account} 一行里有 available / frozen 两个子账，{@code ledger_posting} 必须指明
 * 本笔分录动的是哪一个，否则“下单冻结：available↓ / frozen↑”无法表达，
 * 也无法回答 {@code balance_after} 到底是哪个子账的余额。
 * <p>
 * 落库值见 {@link LedgerConsts.BalanceType}，与 {@code ledger_posting.balance_type} 对应。
 */
public enum BalanceType {

    /** 可用余额 */
    AVAILABLE,

    /** 冻结余额 */
    FROZEN
}
