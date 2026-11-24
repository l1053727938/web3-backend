package com.ray.web3.service.impl;

import com.ray.web3.config.DepositProperties;
import com.ray.web3.config.InfuraProperties;
import com.ray.web3.exception.LedgerPostingException;
import com.ray.web3.mapper.DepositRecordMapper;
import com.ray.web3.model.entity.DepositRecord;
import com.ray.web3.model.entity.LedgerEntry;
import com.ray.web3.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.protocol.Web3j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 充值重组回滚单测（第 5 周 Day 5-6）
 * <p>
 * 重点：先冲正、再置 INVALID；账本无分录时也要能落 INVALID，不能因为冲正失败卡住回滚。
 */
@ExtendWith(MockitoExtension.class)
class DepositReorgServiceImplTest {

    @Mock
    private DepositRecordMapper depositRecordMapper;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private Web3j web3j;

    private DepositReorgServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DepositReorgServiceImpl(depositRecordMapper, ledgerService,
                new DepositProperties(), new InfuraProperties(), web3j);
    }

    @Test
    void 已确认充值回滚应先冲正再置INVALID() {
        when(depositRecordMapper.selectByDepositNo("DP1")).thenReturn(record("DP1", "CREDITED"));
        when(depositRecordMapper.markInvalid("DP1")).thenReturn(1);
        when(ledgerService.reverse(eq("DEPOSIT"), eq("DP1"))).thenReturn(new LedgerEntry());

        assertThat(service.markReorged("DP1")).isTrue();

        verify(ledgerService).reverse("DEPOSIT", "DP1");
        verify(depositRecordMapper).markInvalid("DP1");
    }

    @Test
    void 账本无分录时仍应置INVALID() {
        when(depositRecordMapper.selectByDepositNo("DP2")).thenReturn(record("DP2", "CREDITED"));
        when(depositRecordMapper.markInvalid("DP2")).thenReturn(1);
        when(ledgerService.reverse(any(), any()))
                .thenThrow(new LedgerPostingException("原分录不存在"));

        assertThat(service.markReorged("DP2")).isTrue();

        verify(depositRecordMapper).markInvalid("DP2");
    }

    @Test
    void 已终态记录不再处理() {
        when(depositRecordMapper.selectByDepositNo("DP3")).thenReturn(record("DP3", "INVALID"));

        assertThat(service.markReorged("DP3")).isFalse();

        verify(depositRecordMapper, never()).markInvalid(any());
        verify(ledgerService, never()).reverse(any(), any());
    }

    private static DepositRecord record(String depositNo, String status) {
        DepositRecord record = new DepositRecord();
        record.setDepositNo(depositNo);
        record.setStatus(status);
        record.setTransactionHash("0x" + depositNo);
        record.setLogIndex(1L);
        record.setBlockNumber(100L);
        return record;
    }
}
