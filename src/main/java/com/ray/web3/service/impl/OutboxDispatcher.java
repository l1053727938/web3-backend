package com.ray.web3.service.impl;

import com.ray.web3.config.OutboxProperties;
import com.ray.web3.consts.DepositConsts;
import com.ray.web3.consts.OutboxConsts;
import com.ray.web3.mapper.DepositRecordMapper;
import com.ray.web3.mapper.OutboxMessageMapper;
import com.ray.web3.model.entity.OutboxMessage;
import com.ray.web3.service.OutboxPublisher;
import com.ray.web3.util.OutboxBackoff;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 投递器（第 3 周 Day 3-4）
 * <p>
 * 每轮在一个事务里完成三件事：
 * <ol>
 *   <li>按 {@code status='NEW' AND next_retry_at <= NOW()} 扫描一批消息，并用
 *       {@code FOR UPDATE SKIP LOCKED} 锁住；多实例部署时各拿各的批次，不会抢同一条；</li>
 *   <li>逐条调用 {@link OutboxPublisher} 投递；</li>
 *   <li>成功置 {@code SENT}，失败累计 {@code retry_count} 并按 {@link OutboxBackoff} 推进
 *       {@code next_retry_at}；达到上限置 {@code FAILED}。</li>
 * </ol>
 * <p>
 * <b>为什么投递也放在事务里</b>：{@code SKIP LOCKED} 的行锁只在事务期间有效，
 * 若“查完就提交”再投递，其他实例会立刻拿到同一批消息。当前通道是 Redis（本地/低延迟），
 * 事务持有时间可控；接 Kafka 后可改成“事务内标记 SENDING、事务外发送、再回写 SENT”，
 * 但需要额外的租约/超时字段，留到后续阶段。
 * <p>
 * <b>至少一次语义</b>：投递成功但事务提交前宕机，消息仍是 NEW，重启后会再发一次；
 * 重复由消费端 {@code consumer_biz} 主键冲突挡住。
 * <p>
 * <b>notify_status</b>：充值消息投递成功/失败时同步回填 {@code deposit_record.notify_status}，
 * 保留原有“业务侧 + 投递侧”双层可观测：outbox_message 看消息生命周期，
 * deposit_record.notify_status 让充值列表直接看出下游通知结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxDispatcher {

    private final OutboxMessageMapper outboxMessageMapper;
    private final OutboxPublisher outboxPublisher;
    private final DepositRecordMapper depositRecordMapper;
    private final OutboxProperties outboxProperties;

    /**
     * 扫描并投递一批消息；返回本轮投递成功的条数。
     * <p>
     * 单条投递失败不会抛异常（否则整批回滚、失败计数也丢失），而是更新重试信息后继续下一条；
     * 数据库更新本身失败才向上抛出，让本轮整体回滚、消息保持 NEW 等下一轮。
     */
    @Transactional(rollbackFor = Exception.class)
    public int dispatchBatch() {
        int limit = Math.max(1, outboxProperties.getBatchSize());
        List<OutboxMessage> batch = outboxMessageMapper.selectPendingBatch(limit);
        if (batch.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (OutboxMessage message : batch) {
            if (deliver(message)) {
                sent++;
            }
        }
        log.info("[outbox] 本轮扫描 {} 条，投递成功 {} 条", batch.size(), sent);
        return sent;
    }

    private boolean deliver(OutboxMessage message) {
        try {
            outboxPublisher.publish(message);
        } catch (Exception e) {
            handleFailure(message, e);
            return false;
        }

        int updated = outboxMessageMapper.markSent(message.getId());
        if (updated == 0) {
            // 行已被本事务锁住，正常不会发生；记录后继续，避免影响同批其他消息
            log.warn("[outbox] 标记 SENT 未命中 id={} bizNo={}", message.getId(), message.getBizNo());
        }
        updateDepositNotifyStatus(message, DepositConsts.Notify.SENT, LocalDateTime.now());
        log.info("[outbox] 投递成功 id={} bizType={} bizNo={} retry={}",
                message.getId(), message.getBizType(), message.getBizNo(), message.getRetryCount());
        return true;
    }

    /**
     * 失败处理：累计次数 → 计算退避秒数 → 未达上限保持 NEW，达到上限置 FAILED；
     * 同时把充值记录的 notify_status 置 FAILED，便于业务侧直接观察。
     * <p>
     * 退避秒数只做数值计算，{@code next_retry_at} 由 SQL 用 {@code NOW(3)} 生成，
     * 与扫描条件同一数据库时钟，不依赖 JVM/MySQL 时区一致。
     */
    private void handleFailure(OutboxMessage message, Exception cause) {
        int retryCount = (message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1;
        long delaySeconds = OutboxBackoff.delaySeconds(retryCount,
                outboxProperties.getRetryBaseSeconds(), outboxProperties.getRetryMaxSeconds());

        if (retryCount >= outboxProperties.getMaxRetries()) {
            outboxMessageMapper.markFailed(message.getId(), delaySeconds);
            log.error("[outbox] 投递失败且达到重试上限({})，标记 FAILED id={} bizType={} bizNo={}: {}",
                    outboxProperties.getMaxRetries(), message.getId(), message.getBizType(),
                    message.getBizNo(), cause.getMessage());
        } else {
            outboxMessageMapper.markRetry(message.getId(), delaySeconds);
            log.warn("[outbox] 投递失败，{} 秒后重试 id={} bizType={} bizNo={} retry={}: {}",
                    delaySeconds, message.getId(), message.getBizType(), message.getBizNo(),
                    retryCount, cause.getMessage());
        }

        updateDepositNotifyStatus(message, DepositConsts.Notify.FAILED, null);
    }

    /**
     * 充值消息投递结果回填 deposit_record.notify_status；
     * 非充值类型直接跳过，其他业务的通知状态由各自业务表负责
     */
    private void updateDepositNotifyStatus(OutboxMessage message, String status, LocalDateTime notifyAt) {
        if (!OutboxConsts.BizType.DEPOSIT.equals(message.getBizType())) {
            return;
        }
        int updated = depositRecordMapper.updateNotifyStatusByDepositNo(message.getBizNo(), status, notifyAt);
        if (updated == 0) {
            log.warn("[outbox] 回填充值通知状态未命中 depositNo={} status={}",
                    message.getBizNo(), status);
        }
    }
}