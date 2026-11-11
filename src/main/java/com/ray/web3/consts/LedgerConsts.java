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
        /** 解冻（提币失败/撤单释放），与 FREEZE 分开避免同单号撞 uk_biz */
        public static final String UNFREEZE = "UNFREEZE";
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

    /**
     * 账户类型（account.account_type）
     * <p>
     * 用户侧是 FUND/SPOT/PERP；平台侧账户（代收负债、手续费收入、待结算）统一用 PLATFORM。
     * 平台负债在“资产口径”下天然是负数（充值越多越负），所以 PLATFORM 账户允许余额为负，
     * 否则充值落账的分录（用户 + / 平台负债 -）永远记不进去。
     */
    public static final class AccountType {
        public static final String FUND = "FUND";
        public static final String SPOT = "SPOT";
        public static final String PERP = "PERP";
        /** 平台账户：允许负余额 */
        public static final String PLATFORM = "PLATFORM";

        private AccountType() {
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
