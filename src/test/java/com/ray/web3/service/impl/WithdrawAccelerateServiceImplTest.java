package com.ray.web3.service.impl;

import com.ray.web3.config.WithdrawProperties;
import com.ray.web3.mapper.WithdrawRecordMapper;
import com.ray.web3.consts.WithdrawConsts;
import com.ray.web3.model.entity.WithdrawRecord;
import com.ray.web3.service.Erc20WithdrawService;
import com.ray.web3.service.WithdrawTrackingService;
import com.ray.web3.service.WithdrawTrackingService.StuckState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 卡单自动加速决策离线单测（第 3 周 Day 5-7）：全部依赖 Mock，不连 MySQL 也不发真实交易
 * <p>
 * 锁死四条红线：只碰年龄超阈的卡单；只有排队/被丢弃才提价重播；被覆盖只标注<b>不作废</b>；
 * 次数用完或异常时绝不越界操作状态
 */
@ExtendWith(MockitoExtension.class)
class WithdrawAccelerateServiceImplTest {

    private static final String WITHDRAW_NO = "WD20261103120000ffffffffffffffff";
    private static final String TX = "0xaaa1";
    private static final int BATCH = 20;

    @Mock
    private WithdrawRecordMapper withdrawRecordMapper;
    @Mock
    private WithdrawTrackingService withdrawTrackingService;
    @Mock
    private Erc20WithdrawService erc20WithdrawService;

    private WithdrawProperties properties;
    private WithdrawAccelerateServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new WithdrawProperties();
        service = new WithdrawAccelerateServiceImpl(withdrawRecordMapper, properties,
                withdrawTrackingService, erc20WithdrawService);
    }

    @Test
    void 只扫描年龄超过卡单阈值的广播记录() {
        givenStuckBatch(broadcast(0));
        when(withdrawTrackingService.diagnoseStuck(WITHDRAW_NO)).thenReturn(StuckState.AWAITING_CONFIRMATION);

        service.accelerateStuckBroadcasts();

        ArgumentCaptor<LocalDateTime> before = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(withdrawRecordMapper).selectStuck(eq(WithdrawConsts.Status.BROADCAST), before.capture(), eq(BATCH));
        // 入参必须是 now - 卡单阈值：早于它就说明把刚广播的正常交易也拉去提价了
        long secondsAhead = java.time.Duration.between(before.getValue(), LocalDateTime.now()).toSeconds();
        assertThat(secondsAhead).isBetween(properties.getTrackStuckTimeoutMs() / 1000 - 5L,
                properties.getTrackStuckTimeoutMs() / 1000 + 5L);
    }

    @Test
    void 排队与被丢弃应触发同nonce提价重播() {
        for (StuckState state : List.of(StuckState.QUEUED, StuckState.DROPPED)) {
            org.mockito.Mockito.reset(withdrawRecordMapper, withdrawTrackingService, erc20WithdrawService);
            givenStuckBatch(broadcast(1));
            when(withdrawTrackingService.diagnoseStuck(WITHDRAW_NO)).thenReturn(state);

            int acted = service.accelerateStuckBroadcasts();

            assertThat(acted).as("成因 %s 应计入处置", state).isEqualTo(1);
            verify(erc20WithdrawService).accelerateWithdraw(WITHDRAW_NO);
            // 重播只换 gasPrice，状态仍由 BROADCAST 继续等确认，不能在这里落任何终态
            verify(withdrawRecordMapper, never()).markReplaced(anyString(), anyString());
            verify(withdrawRecordMapper, never()).markSuccess(anyString());
            verify(withdrawRecordMapper, never()).markReverted(anyString(), anyString());
        }
    }

    @Test
    void 已中块等确认与节点问不到时不应白花提价() {
        for (StuckState state : List.of(StuckState.AWAITING_CONFIRMATION, StuckState.NODE_UNAVAILABLE)) {
            org.mockito.Mockito.reset(withdrawRecordMapper, withdrawTrackingService, erc20WithdrawService);
            givenStuckBatch(broadcast(0));
            when(withdrawTrackingService.diagnoseStuck(WITHDRAW_NO)).thenReturn(state);

            assertThat(service.accelerateStuckBroadcasts()).isZero();

            verify(erc20WithdrawService, never()).accelerateWithdraw(anyString());
            verify(withdrawRecordMapper, never()).markStuck(anyString(), anyString());
        }
    }

    @Test
    void 疑似被外部交易覆盖时只标注不作废() {
        givenStuckBatch(broadcast(0));
        when(withdrawTrackingService.diagnoseStuck(WITHDRAW_NO)).thenReturn(StuckState.REPLACED);
        when(withdrawRecordMapper.markStuck(eq(WITHDRAW_NO), anyString())).thenReturn(1);

        int acted = service.accelerateStuckBroadcasts();

        // 作废带资金语义（要触发解冻退款），不能凭单次节点视图落 REPLACED，只留标注给人工
        verify(withdrawRecordMapper, never()).markReplaced(anyString(), anyString());
        verify(erc20WithdrawService, never()).accelerateWithdraw(anyString());
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(withdrawRecordMapper).markStuck(eq(WITHDRAW_NO), note.capture());
        assertThat(note.getValue()).contains("卡单待人工").contains("疑似被同 nonce");
        assertThat(acted).isEqualTo(1);
    }

    @Test
    void 达加速上限应停止重播并标注转人工() {
        WithdrawRecord record = broadcast(properties.getAccelerateMaxCount());
        givenStuckBatch(record);
        when(withdrawRecordMapper.markStuck(eq(WITHDRAW_NO), anyString())).thenReturn(1);

        int acted = service.accelerateStuckBroadcasts();

        assertThat(acted).isEqualTo(1);
        // 预算耗尽后连诊断 RPC 都不该再花：结论已经确定是"转人工"
        verify(withdrawTrackingService, never()).diagnoseStuck(anyString());
        verify(erc20WithdrawService, never()).accelerateWithdraw(anyString());
        verify(withdrawRecordMapper).markStuck(eq(WITHDRAW_NO), anyString());
    }

    @Test
    void 标注已存在时不重复刷库() {
        // markStuck 带 error_message IS NULL 条件：命中 0 行 = 这笔早已标注过，正在等人工
        WithdrawRecord record = broadcast(properties.getAccelerateMaxCount());
        record.setErrorMessage("卡单待人工：已达自动加速上限");
        givenStuckBatch(record);
        when(withdrawRecordMapper.markStuck(eq(WITHDRAW_NO), anyString())).thenReturn(0);

        assertThat(service.accelerateStuckBroadcasts()).isZero();
    }

    @Test
    void 单条重播失败不应中断整轮也不改状态() {
        givenStuckBatch(broadcast(0));
        when(withdrawTrackingService.diagnoseStuck(WITHDRAW_NO)).thenReturn(StuckState.QUEUED);
        doThrow(new IllegalStateException("替换交易被节点拒绝: underpriced"))
                .when(erc20WithdrawService).accelerateWithdraw(WITHDRAW_NO);

        // 抛错被循环吞掉：本轮不计动作数，记录保持 BROADCAST 等下一轮重来
        assertThat(service.accelerateStuckBroadcasts()).isZero();
        verify(withdrawRecordMapper, never()).markAccelerated(anyString(), anyString(), any());
        verify(withdrawRecordMapper, never()).markReplaced(anyString(), anyString());
    }

    @Test
    void 没有卡单时不应对节点做任何查询() {
        when(withdrawRecordMapper.selectStuck(eq(WithdrawConsts.Status.BROADCAST), any(), anyInt()))
                .thenReturn(List.of());

        assertThat(service.accelerateStuckBroadcasts()).isZero();
        verify(withdrawTrackingService, never()).diagnoseStuck(anyString());
        verify(erc20WithdrawService, never()).accelerateWithdraw(anyString());
    }

    private void givenStuckBatch(WithdrawRecord record) {
        when(withdrawRecordMapper.selectStuck(eq(WithdrawConsts.Status.BROADCAST), any(), anyInt()))
                .thenReturn(List.of(record));
    }

    private WithdrawRecord broadcast(int acceleratedTimes) {
        WithdrawRecord record = new WithdrawRecord();
        record.setWithdrawNo(WITHDRAW_NO);
        record.setChainId(31337L);
        record.setFromAddress("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
        record.setToAddress("0x70997970c51812dc3a010c7d01b50e0d17dc79c8");
        record.setContractAddress("0x5fbdb2315678afecb367f032d93f642f64180aa3");
        record.setRawValue("1000000");
        record.setNonce(BigInteger.valueOf(7L));
        record.setGasLimit(BigInteger.valueOf(60_000L));
        record.setGasPrice(BigInteger.valueOf(1_000_000_000L));
        record.setTransactionHash(TX);
        record.setStatus(WithdrawConsts.Status.BROADCAST);
        record.setAccelerateCount(acceleratedTimes);
        record.setCreatedAt(LocalDateTime.now().minusMinutes(20));
        return record;
    }
}
