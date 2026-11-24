package com.ray.web3.service.impl;

import com.ray.web3.config.DepositProperties;
import com.ray.web3.config.ReconcileProperties;
import com.ray.web3.consts.ReconcileConsts;
import com.ray.web3.mapper.ReconcileDiffMapper;
import com.ray.web3.mapper.ReconcileQueryMapper;
import com.ray.web3.model.entity.DepositRecord;
import com.ray.web3.model.entity.ReconcileDiff;
import com.ray.web3.model.vo.ChainOrphanRow;
import com.ray.web3.model.vo.LedgerAggRow;
import com.ray.web3.service.ReconcileService;
import com.ray.web3.service.TokenMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账任务离线单测（第 5 周 Day 3-4）
 * <p>
 * 一天内同时构造六类差异，断言：分类正确、报表口径正确、净差符号正确、
 * 并且精度差没有被误判成遗漏。
 */
@ExtendWith(MockitoExtension.class)
class ReconcileServiceImplTest {

    private static final String CONTRACT = "0x5fbdb2315678afecb367f032d93f642f64180aa3";
    private static final String UNKNOWN_CONTRACT = "0x00000000000000000000000000000000000000ff";
    private static final LocalDate DATE = LocalDate.of(2026, 11, 24);

    @Mock
    private ReconcileQueryMapper queryMapper;
    @Mock
    private ReconcileDiffMapper diffMapper;
    @Mock
    private TokenMetadataService tokenMetadataService;

    private ReconcileServiceImpl service;

    @BeforeEach
    void setUp() {
        ReconcileProperties properties = new ReconcileProperties();
        properties.setTokenWhitelist(List.of(CONTRACT));
        // 演示精度差：当前 decimals=8，历史快照是 6
        properties.setCurrentDecimals(Map.of(CONTRACT, 8));
        // 测试用小分片，避免起太多线程
        properties.setShards(2);
        properties.setShardThreads(1);
        properties.setQps(1000);

        DepositProperties depositProperties = new DepositProperties();
        depositProperties.getTokenSymbols().put(CONTRACT, "USDT");

        ReconcileShardExecutor shardExecutor = new ReconcileShardExecutor(properties);
        service = new ReconcileServiceImpl(queryMapper, diffMapper, properties,
                depositProperties, tokenMetadataService, shardExecutor);
    }

    @Test
    void 一天内六类差异应全部正确分类且净差符号一致() {
        when(queryMapper.selectDepositByCreatedAt(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(
                        record("T1", "PENDING_CONFIRM", "10.000000", "6", "10000000", "USDT"),
                        record("M1", "CREDITED", "10.000000", "6", "10000000", "USDT"),
                        record("P1", "CREDITED", "10.000000", "6", "10000000", "USDT"),
                        record("D1", "CREDITED", "10.000000", "6", "10000000", "USDT"),
                        record("R1", "INVALID", "10.000000", "6", "10000000", "USDT"),
                        record("U1", "CREDITED", "10.000000", "6", "10000000", null)));

        when(queryMapper.selectLedgerAgg(anyList())).thenReturn(List.of(
                // P1：账本按 decimals=8 重算成 0.1 → 精度差
                ledger("P1", 1L, "0.100000000000000000", 0),
                // D1：同一业务键两组分录 → 重复
                ledger("D1", 2L, "10.000000000000000000", 0),
                ledger("D1", 3L, "10.000000000000000000", 0),
                // R1：已回滚但账本未冲正 → 重组回滚
                ledger("R1", 4L, "10.000000000000000000", 0)));

        when(queryMapper.selectChainOrphans(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(
                        orphan(CONTRACT, "0xo1", 1L, "10.000000"),
                        orphan(UNKNOWN_CONTRACT, "0xo2", 2L, "5.000000")));

        ReconcileService.ReconcileReport report = service.run(DATE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReconcileDiff>> captor = ArgumentCaptor.forClass(List.class);
        verify(diffMapper).insertBatch(captor.capture());
        List<ReconcileDiff> diffs = captor.getValue();

        assertThat(diffs).hasSize(8);
        assertThat(find(diffs, "T1").getDiffType()).isEqualTo(ReconcileConsts.DiffType.TIMING);
        assertThat(find(diffs, "T1").getReportGroup()).isEqualTo(ReconcileConsts.ReportGroup.IN_TRANSIT);

        assertThat(find(diffs, "M1").getDiffType()).isEqualTo(ReconcileConsts.DiffType.MISSING);
        assertThat(find(diffs, "M1").getDirection()).isEqualTo(ReconcileConsts.Direction.SHORT);

        ReconcileDiff precision = find(diffs, "P1");
        assertThat(precision.getDiffType()).isEqualTo(ReconcileConsts.DiffType.PRECISION);
        assertThat(precision.getReportGroup()).isEqualTo(ReconcileConsts.ReportGroup.OVER_SHORT);
        assertThat(precision.getEvidence()).contains("snapshot=6").contains("current=8");
        assertThat(precision.getDelta()).isEqualByComparingTo("-9.9");

        assertThat(find(diffs, "D1").getDiffType()).isEqualTo(ReconcileConsts.DiffType.DUPLICATE);
        assertThat(find(diffs, "D1").getDirection()).isEqualTo(ReconcileConsts.Direction.LONG);

        assertThat(find(diffs, "R1").getDiffType()).isEqualTo(ReconcileConsts.DiffType.REORG);
        assertThat(find(diffs, "R1").getReportGroup()).isEqualTo(ReconcileConsts.ReportGroup.IN_TRANSIT);

        assertThat(find(diffs, "U1").getDiffType()).isEqualTo(ReconcileConsts.DiffType.UNKNOWN_TOKEN);
        assertThat(find(diffs, "U1").getReportGroup()).isEqualTo(ReconcileConsts.ReportGroup.UNDETERMINED);

        assertThat(find(diffs, "CHAIN:0xo1:1").getDiffType()).isEqualTo(ReconcileConsts.DiffType.MISSING);
        assertThat(find(diffs, "CHAIN:0xo2:2").getDiffType()).isEqualTo(ReconcileConsts.DiffType.UNKNOWN_TOKEN);

        // 2 在途 + 4 长短款 + 2 待定性
        assertThat(report.total()).isEqualTo(8);
        assertThat(report.inTransit()).isEqualTo(2);
        assertThat(report.overShort()).isEqualTo(4);
        assertThat(report.undetermined()).isEqualTo(2);
        // -10(T1) -10(M1) -9.9(P1) +10(D1) +10(R1) -10(O1) = -19.9
        assertThat(report.netDelta()).isEqualByComparingTo("-19.9");
    }

    @Test
    void 重跑应先删同日结果再整批重建() {
        when(queryMapper.selectDepositByCreatedAt(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(queryMapper.selectChainOrphans(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        service.run(DATE);

        verify(diffMapper).deleteByDate(DATE);
    }

    private static ReconcileDiff find(List<ReconcileDiff> diffs, String bizNo) {
        return diffs.stream().filter(d -> bizNo.equals(d.getBizNo())).findFirst()
                .orElseThrow(() -> new AssertionError("未找到差异: " + bizNo));
    }

    private static DepositRecord record(String depositNo, String status, String display,
                                        String decimals, String raw, String currency) {
        DepositRecord record = new DepositRecord();
        record.setDepositNo(depositNo);
        record.setStatus(status);
        record.setDisplayValue(display);
        record.setDecimals(Integer.valueOf(decimals));
        record.setRawValue(raw);
        record.setCurrency(currency);
        record.setContractAddress(CONTRACT);
        return record;
    }

    private static LedgerAggRow ledger(String bizNo, Long entryId, String amount, int reversalCount) {
        LedgerAggRow row = new LedgerAggRow();
        row.setBizNo(bizNo);
        row.setEntryId(entryId);
        row.setUserAmount(new BigDecimal(amount));
        row.setReversalCount(reversalCount);
        row.setCurrency("USDT");
        return row;
    }

    private static ChainOrphanRow orphan(String contract, String tx, Long logIndex, String display) {
        ChainOrphanRow row = new ChainOrphanRow();
        row.setContractAddress(contract);
        row.setTransactionHash(tx);
        row.setLogIndex(logIndex);
        row.setDisplayValue(display);
        row.setRawValue("0");
        row.setDecimals(6);
        row.setToAddress("0xa1153393636750230f4db253300e59ca8bd3a9c0");
        return row;
    }
}
