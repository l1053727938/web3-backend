package com.ray.web3.service.impl;

import com.ray.web3.config.WithdrawProperties;
import com.ray.web3.mapper.WithdrawRecordMapper;
import com.ray.web3.model.entity.LedgerEntry;
import com.ray.web3.model.entity.WithdrawRecord;
import com.ray.web3.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 提币失败补偿单测（第 4 周 Day 3-4）
 * <p>
 * 锁死“补偿成功才置 REFUNDED”：账本调用失败时状态必须保持 NONE，否则下一轮不会再重试，
 * 用户冻结资金会永久卡住。
 */
@ExtendWith(MockitoExtension.class)
class WithdrawRefundServiceImplTest {

    @Mock
    private WithdrawRecordMapper withdrawRecordMapper;
    @Mock
    private LedgerService ledgerService;

    private WithdrawRefundServiceImpl service;

    @BeforeEach
    void setUp() {
        WithdrawProperties properties = new WithdrawProperties();
        properties.setRefundBatchSize(100);
        service = new WithdrawRefundServiceImpl(withdrawRecordMapper, ledgerService, properties);
    }

    @Test
    void 补偿成功应标记REFUNDED_无冻结分录也算处理完成() {
        when(withdrawRecordMapper.selectFailedUnrefunded(100)).thenReturn(List.of(
                failedWithdraw("WD1"), failedWithdraw("WD2")));
        when(ledgerService.refundWithdrawFreeze("WD1")).thenReturn(entry("LE1"));
        when(ledgerService.refundWithdrawFreeze("WD2")).thenReturn(null);

        int refunded = service.refundFailedWithdrawals();

        assertThat(refunded).isEqualTo(2);
        verify(withdrawRecordMapper).markRefunded("WD1");
        verify(withdrawRecordMapper).markRefunded("WD2");
    }

    @Test
    void 账本失败应保留待重试不标记REFUNDED() {
        when(withdrawRecordMapper.selectFailedUnrefunded(100))
                .thenReturn(List.of(failedWithdraw("WD3")));
        when(ledgerService.refundWithdrawFreeze("WD3"))
                .thenThrow(new IllegalStateException("ledger down"));

        int refunded = service.refundFailedWithdrawals();

        assertThat(refunded).isZero();
        verify(withdrawRecordMapper, never()).markRefunded("WD3");
    }

    @Test
    void 无待补偿记录时直接返回() {
        when(withdrawRecordMapper.selectFailedUnrefunded(100)).thenReturn(List.of());

        assertThat(service.refundFailedWithdrawals()).isZero();
        verifyNoInteractions(ledgerService);
    }

    private static WithdrawRecord failedWithdraw(String withdrawNo) {
        WithdrawRecord record = new WithdrawRecord();
        record.setWithdrawNo(withdrawNo);
        record.setStatus("FAILED");
        record.setRefundStatus("NONE");
        return record;
    }

    private static LedgerEntry entry(String entryNo) {
        LedgerEntry entry = new LedgerEntry();
        entry.setEntryNo(entryNo);
        return entry;
    }
}
