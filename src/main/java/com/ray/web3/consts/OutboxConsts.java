package com.ray.web3.consts;

/**
 * Outbox 消息常量（第 3 周 Day 1-2）
 * <p>
 * 与 {@code sql/schema.sql} 中 {@code outbox_message} 的枚举列取值一一对应，
 * 避免业务代码、投递器、对账脚本三处字面量漂移。
 */
public final class OutboxConsts {

    private OutboxConsts() {
    }

    /** 业务类型（outbox_message.biz_type） */
    public static final class BizType {
        /** 充值入账通知 */
        public static final String DEPOSIT = "DEPOSIT";

        private BizType() {
        }
    }

    /** 投递状态（outbox_message.status） */
    public static final class Status {
        /** 待投递：投递器扫描的入口状态 */
        public static final String NEW = "NEW";
        /** 已投递：投递成功后的终态 */
        public static final String SENT = "SENT";
        /** 重试超限：不再自动投递，等待人工介入/补偿 */
        public static final String FAILED = "FAILED";

        private Status() {
        }
    }
}