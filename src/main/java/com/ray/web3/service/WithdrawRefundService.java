package com.ray.web3.service;

/**
 * 提币失败补偿（第 4 周 Day 3-4）
 * <p>
 * 只处理 {@code withdraw_record.status='FAILED' AND refund_status='NONE'} 的记录：
 * 按账本原冻结分录反查账户与金额，自动解冻，并把 {@code refund_status} 置 REFUNDED。
 * <p>
 * 与“通知失败”无关：{@code outbox_message.status=FAILED} / {@code deposit_record.notify_status=FAILED}
 * 是消息投递失败，不会触发资金补偿；本服务只认提币业务失败。
 */
public interface WithdrawRefundService {

    /**
     * 扫描并补偿一批失败提币
     *
     * @return 本轮成功处理（含“无需解冻”）的条数
     */
    int refundFailedWithdrawals();
}
