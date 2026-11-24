package com.ray.web3.consts;

/**
 * 对账常量（第 5 周 Day 3-4）
 * <p>
 * 六类是“判定结果”，三类是“报表口径”。不要混用：
 * 日报只输出在途/长短款/待定性，六类只用于定位原因。
 */
public final class ReconcileConsts {

    private ReconcileConsts() {
    }

    /** 六类差错（reconcile_diff.diff_type） */
    public static final class DiffType {
        /** 时间差：未到确认数，账本尚未入账 */
        public static final String TIMING = "TIMING";
        /** 遗漏：链上已确认，账本没有 */
        public static final String MISSING = "MISSING";
        /** 重复：账本同一业务键出现多组分录 */
        public static final String DUPLICATE = "DUPLICATE";
        /** 重组回滚：曾入账后被链重组撤销 */
        public static final String REORG = "REORG";
        /** 未知代币：合约不在白名单，无法确定币种/精度 */
        public static final String UNKNOWN_TOKEN = "UNKNOWN_TOKEN";
        /** 精度差：差额可由 decimals 快照与当前值解释 */
        public static final String PRECISION = "PRECISION";

        private DiffType() {
        }
    }

    /** 三类报表（reconcile_diff.report_group） */
    public static final class ReportGroup {
        /** 在途：时间差 + 重组回滚 */
        public static final String IN_TRANSIT = "IN_TRANSIT";
        /** 长短款：遗漏 + 重复 + 精度差 */
        public static final String OVER_SHORT = "OVER_SHORT";
        /** 待定性：未知代币及无法分类 */
        public static final String UNDETERMINED = "UNDETERMINED";

        private ReportGroup() {
        }
    }

    /** 方向（reconcile_diff.direction）：delta = 账本 - 链上 */
    public static final class Direction {
        /** 长款：账上比链上多 */
        public static final String LONG = "LONG";
        /** 短款：账上比链上少 */
        public static final String SHORT = "SHORT";

        private Direction() {
        }
    }

    /** 处置状态（reconcile_diff.status） */
    public static final class Status {
        public static final String OPEN = "OPEN";
        public static final String RESOLVED = "RESOLVED";
        public static final String IGNORED = "IGNORED";

        private Status() {
        }
    }

    /** 链上孤儿（没有 deposit_record 的事件）的合成单号前缀 */
    public static final String CHAIN_ORPHAN_PREFIX = "CHAIN:";
}
