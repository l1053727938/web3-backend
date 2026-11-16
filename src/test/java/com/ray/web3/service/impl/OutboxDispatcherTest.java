package com.ray.web3.service.impl;

import com.ray.web3.config.OutboxProperties;
import com.ray.web3.consts.DepositConsts;
import com.ray.web3.consts.OutboxConsts;
import com.ray.web3.mapper.DepositRecordMapper;
import com.ray.web3.mapper.OutboxMessageMapper;
import com.ray.web3.model.entity.OutboxMessage;
import com.ray.web3.service.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Outbox 投递器离线单测（第 3 周 Day 3-4）
 * <p>
 * 锁死状态机：成功 NEW→SENT；失败 retry_count+1 且保持 NEW（按退避推迟 next_retry_at）；
 * 达到上限 NEW→FAILED；充值消息同步回填 deposit_record.notify_status，其他业务类型不碰充值表。
 * SQL 层的 FOR UPDATE SKIP LOCKED 由 Mapper 注解保证，这里只验证投递编排。
 */
@ExtendWith(MockitoExtension.class)
class OutboxDispatcherTest {

    @Mock
    private OutboxMessageMapper outboxMessageMapper;
    @Mock
    private OutboxPublisher outboxPublisher;
    @Mock
    private DepositRecordMapper depositRecordMapper;

    private OutboxProperties properties;
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        properties = new OutboxProperties();
        properties.setBatchSize(200);
        properties.setMaxRetries(3);
        properties.setRetryBaseSeconds(5);
        properties.setRetryMaxSeconds(300);
        dispatcher = new OutboxDispatcher(outboxMessageMapper, outboxPublisher, depositRecordMapper, properties);
    }

    @Test
    void 投递成功标记SENT并回填充值通知状态() throws Exception {
        OutboxMessage message = message(1L, OutboxConsts.BizType.DEPOSIT, "DP1", 0);
        when(outboxMessageMapper.selectPendingBatch(200)).thenReturn(List.of(message));
        when(outboxMessageMapper.markSent(1L)).thenReturn(1);
        when(depositRecordMapper.updateNotifyStatusByDepositNo(
                eq("DP1"), eq(DepositConsts.Notify.SENT), any(LocalDateTime.class))).thenReturn(1);

        int sent = dispatcher.dispatchBatch();

        assertThat(sent).isEqualTo(1);
        verify(outboxPublisher).publish(message);
        verify(outboxMessageMapper).markSent(1L);
        verify(depositRecordMapper).updateNotifyStatusByDepositNo(
                eq("DP1"), eq(DepositConsts.Notify.SENT), any(LocalDateTime.class));
        verify(outboxMessageMapper, never()).markRetry(any(), anyLong());
    }

    @Test
    void 投递失败按退避安排重试并标记通知FAILED() throws Exception {
        OutboxMessage message = message(2L, OutboxConsts.BizType.DEPOSIT, "DP2", 0);
        when(outboxMessageMapper.selectPendingBatch(200)).thenReturn(List.of(message));
        doThrow(new IllegalStateException("redis down")).when(outboxPublisher).publish(message);
        when(outboxMessageMapper.markRetry(eq(2L), eq(5L))).thenReturn(1);
        when(depositRecordMapper.updateNotifyStatusByDepositNo(
                eq("DP2"), eq(DepositConsts.Notify.FAILED), isNull())).thenReturn(1);

        int sent = dispatcher.dispatchBatch();

        assertThat(sent).isZero();
        // 第 1 次失败：base=5s；具体时刻由 SQL 的 NOW(3)+INTERVAL 计算
        verify(outboxMessageMapper).markRetry(2L, 5L);
        verify(depositRecordMapper).updateNotifyStatusByDepositNo(
                eq("DP2"), eq(DepositConsts.Notify.FAILED), isNull());
        verify(outboxMessageMapper, never()).markFailed(any(), anyLong());
    }

    @Test
    void 达到最大重试次数标记FAILED() throws Exception {
        // maxRetries=3，本条已有 2 次失败，本次失败后累计 3 次 → 终态；第 3 次退避 20s
        OutboxMessage message = message(3L, OutboxConsts.BizType.DEPOSIT, "DP3", 2);
        when(outboxMessageMapper.selectPendingBatch(200)).thenReturn(List.of(message));
        doThrow(new IllegalStateException("still down")).when(outboxPublisher).publish(message);
        when(outboxMessageMapper.markFailed(eq(3L), eq(20L))).thenReturn(1);
        when(depositRecordMapper.updateNotifyStatusByDepositNo(
                eq("DP3"), eq(DepositConsts.Notify.FAILED), isNull())).thenReturn(1);

        assertThat(dispatcher.dispatchBatch()).isZero();

        verify(outboxMessageMapper).markFailed(3L, 20L);
        verify(outboxMessageMapper, never()).markRetry(any(), anyLong());
    }

    @Test
    void 非充值消息只更新outbox不碰充值通知状态() throws Exception {
        OutboxMessage message = message(4L, "MATCH", "MATCH-1", 0);
        when(outboxMessageMapper.selectPendingBatch(200)).thenReturn(List.of(message));
        when(outboxMessageMapper.markSent(4L)).thenReturn(1);

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

        verify(outboxPublisher).publish(message);
        verify(outboxMessageMapper).markSent(4L);
        verify(depositRecordMapper, never()).updateNotifyStatusByDepositNo(any(), any(), any());
    }

    @Test
    void 空批次不触发任何投递() {
        when(outboxMessageMapper.selectPendingBatch(200)).thenReturn(List.of());

        assertThat(dispatcher.dispatchBatch()).isZero();
        verifyNoInteractions(outboxPublisher, depositRecordMapper);
    }

    @Test
    void dispatchBatch必须声明回滚所有异常的事务() throws Exception {
        Transactional tx = OutboxDispatcher.class.getMethod("dispatchBatch").getAnnotation(Transactional.class);

        assertThat(tx).as("SELECT FOR UPDATE SKIP LOCKED 的行锁必须在事务内，投递与状态回写同事务").isNotNull();
        assertThat(tx.rollbackFor()).contains(Exception.class);
    }

    private static OutboxMessage message(Long id, String bizType, String bizNo, int retryCount) {
        OutboxMessage message = new OutboxMessage();
        message.setId(id);
        message.setBizType(bizType);
        message.setBizNo(bizNo);
        message.setPayload("{}");
        message.setStatus(OutboxConsts.Status.NEW);
        message.setRetryCount(retryCount);
        message.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        return message;
    }
}