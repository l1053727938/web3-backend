package com.ray.web3.model;

/**
 * 充值业务常量（REQ-BIZ-04 模块四）
 * <p>
 * 与 sql/schema.sql 中 deposit_wallet/deposit_record 的枚举列取值一一对应，
 * 集中定义避免配置、落库、下游消息三处字面量漂移
 */
public final class DepositConsts {

    private DepositConsts() {
    }

    /** 充值地址类型（deposit_wallet.wallet_type） */
    public static final class WalletType {
        /** 平台热钱包：多人充值的公共归集地址 */
        public static final String HOT = "HOT";
        /** 用户专属充值地址 */
        public static final String USER = "USER";

        private WalletType() {
        }

        public static boolean isValid(String type) {
            return HOT.equals(type) || USER.equals(type);
        }
    }

    /** 充值状态（deposit_record.status） */
    public static final class Status {
        /** 待确认：已落库，确认数未达阈值（验收标准要求的初始状态） */
        public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
        /** 已入账：下游账务处理完成 */
        public static final String CREDITED = "CREDITED";
        /** 已作废：链重组导致日志失效 */
        public static final String INVALID = "INVALID";

        private Status() {
        }
    }

    /** 事件来源（deposit_record.event_source） */
    public static final class Source {
        /** WebSocket 实时订阅（模块三） */
        public static final String STREAM = "STREAM";
        /** 历史补拉（模块二） */
        public static final String BACKFILL = "BACKFILL";

        private Source() {
        }
    }

    /** 下游通知状态（deposit_record.notify_status） */
    public static final class Notify {
        /** 未发布 */
        public static final String INIT = "INIT";
        /** 已发布到 Redis 频道 */
        public static final String SENT = "SENT";
        /** 发布失败，待补偿重发 */
        public static final String FAILED = "FAILED";

        private Notify() {
        }
    }
}
