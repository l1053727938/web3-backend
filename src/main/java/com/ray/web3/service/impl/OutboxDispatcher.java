package com.ray.web3.service.impl;

import com.ray.web3.config.OutboxProperties;
import com.ray.web3.consts.DepositConsts;
import com.ray.web3.consts.OutboxConsts;
import com.ray.web3.mapper.DepositRecordMapper;
import com.ray.web3.mapper.DlqMessageMapper;
import com.ray.web3.mapper.OutboxMessageMapper;
import com.ray.web3.model.entity.DlqMessage;
import com.ray.web3.model.entity.OutboxMessage;
import com.ray.web3.service.OutboxPublisher;
import com.ray.web3.util.RetryPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 *   <li>成功置 {@code SENT}，失败累计 {@code retry_count} 并按 {@link RetryPolicy}
 *       推进 {@code next_retry_at}（指数退避 + jitter）；达到上限写 {@code dlq_message}
 *       并把原消息置 {@code FAILED}（<b>不删除原消息</b>）。</li>
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
public class OutboxDispatcher {

    /** 死信错误信息截断长度，对齐 dlq_message.error_message VARCHAR(512) */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 512;

    private final OutboxMessageMapper outboxMessageMapper;
    private final OutboxPublisher outboxPublisher;
    private final DepositRecordMapper depositRecordMapper;
    private final DlqMessageMapper dlqMessageMapper;
    private final OutboxProperties outboxProperties;
    private final RetryPolicy retryPolicy;

    /** 死信告警信号：进入 dlq 的消息数，非 0 就应触发人工处理 */
    private final Counter dlqCounter;

    public OutboxDispatcher(OutboxMessageMapper outboxMessageMapper,
                            OutboxPublisher outboxPublisher,
                            DepositRecordMapper depositRecordMapper,
                            DlqMessageMapper dlqMessageMapper,
                            OutboxProperties outboxProperties,
                            RetryPolicy retryPolicy,
                            MeterRegistry meterRegistry) {
        this.outboxMessageMapper = outboxMessageMapper;
        this.outboxPublisher = outboxPublisher;
        this.depositRecordMapper = depositRecordMapper;
        this.dlqMessageMapper = dlqMessageMapper;
        this.outboxProperties = outboxProperties;
        this.retryPolicy = retryPolicy;
        this.dlqCounter = Counter.builder("outbox.dlq")
                .description("进入死信队列的消息数（重试超限）")
                .register(meterRegistry);
    }

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
        long delaySeconds = retryPolicy.delaySeconds(retryCount);

        if (retryPolicy.isExhausted(retryCount)) {
            // 先写死信、再置 FAILED，且都在当前事务内：任何一步失败整轮回滚，
            // 消息保持 NEW 继续重试，绝不会出现“原消息没了、死信也没写”的丢消息
            moveToDlq(message, retryCount, cause);
            outboxMessageMapper.markFailed(message.getId(), delaySeconds);
            log.error("[outbox][dlq] 投递失败达到上限({})，已写死信并保留原消息 FAILED "
                            + "id={} bizType={} bizNo={}: {}",
                    retryPolicy.maxAttempts(), message.getId(), message.getBizType(),
                    message.getBizNo(), cause.getMessage());
        } else {
            outboxMessageMapper.markRetry(message.getId(), delaySeconds);
            log.warn("[outbox] 投递失败，{}s 后重试（含抖动） id={} bizType={} bizNo={} retry={}/{}: {}",
                    delaySeconds, message.getId(), message.getBizType(), message.getBizNo(),
                    retryCount, retryPolicy.maxAttempts(), cause.getMessage());
        }

        updateDepositNotifyStatus(message, DepositConsts.Notify.FAILED, null);
    }

    /**
     * 写死信：原 outbox_message 不删除，只置 FAILED；死信用 {@code uk_source} 幂等，
     * 重复处理不会产生多条。
     */
    private void moveToDlq(OutboxMessage message, int retryCount, Exception cause) {
        DlqMessage dlq = new DlqMessage();
        dlq.setSourceTable(OutboxConsts.SourceTable.OUTBOX);
        dlq.setSourceId(message.getId());
        dlq.setBizType(message.getBizType());
        dlq.setBizNo(message.getBizNo());
        dlq.setPartitionKey(message.getPartitionKey());
        dlq.setPayload(message.getPayload());
        dlq.setRetryCount(retryCount);
        dlq.setErrorMessage(truncate(cause.getMessage(), ERROR_MESSAGE_MAX_LENGTH));
        dlq.setStatus(OutboxConsts.DlqStatus.OPEN);
        dlqMessageMapper.insertIgnore(dlq);
        dlqCounter.increment();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
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