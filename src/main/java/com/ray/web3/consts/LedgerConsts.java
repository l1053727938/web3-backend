package com.ray.web3.consts;

/**
 * 复式账本常量（第 2 周 U1-b）
 * <p>
 * 与 sql/schema.sql 中 ledger_entry / ledger_posting 的枚举列取值一一对应，
 * 集中定义避免业务层、落库 SQL、对账脚本三处字面量漂移。
 */
public final class LedgerConsts {

    private LedgerConsts() {
    }

    /** 业务类型（ledger_entry.biz_type）：幂等键 (biz_type, biz_no) 的一半 */
    public static final class BizType {
        /** 充值落账 */
        public static final String DEPOSIT = "DEPOSIT";
        /** 账户间划转 */
        public static final String TRANSFER = "TRANSFER";
        /** 下单/提币冻结 */
        public static final String FREEZE = "FREEZE";
        /** 手续费 */
        public static final String FEE = "FEE";
        /** 冲正 */
        public static final String REVERSE = "REVERSE";

        private BizType() {
        }
    }

    /** 分录组状态（ledger_entry.status） */
    public static final class EntryStatus {
        /** 已生效 */
        public static final String SUCCESS = "SUCCESS";
        /** 已被冲正（原分录不改，靠 reverse_of 指向的反向组表达） */
        public static final String REVERSED = "REVERSED";

        private EntryStatus() {
        }
    }

    /** 子账类型（ledger_posting.balance_type） */
    public static final class BalanceType {
        /** 可用余额 */
        public static final String AVAILABLE = "AVAILABLE";
        /** 冻结余额 */
        public static final String FROZEN = "FROZEN";

        private BalanceType() {
        }
    }
}
