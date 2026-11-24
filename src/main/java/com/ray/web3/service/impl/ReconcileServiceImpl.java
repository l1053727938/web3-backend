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
import com.ray.web3.util.MoneyUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对账任务实现（第 5 周 Day 3-4）
 * <p>
 * 判定顺序固定：未知代币 → 重组回滚 → 时间差 → 重复 → 遗漏 → 精度差 → 待定性。
 * 先判“暂时状态/无法定性”，再判真差异，避免一笔差异被重复计入多个报表口径。
 * <p>
 * 精度差专项：历史金额只用 {@code deposit_record.decimals} 快照重算；当“当前 decimals”
 * 与快照不同、且按当前值重算的金额正好等于账本金额时，判定为 PRECISION 而不是遗漏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileServiceImpl implements ReconcileService {

    private static final String STATUS_PENDING = "PENDING_CONFIRM";
    private static final String STATUS_CREDITED = "CREDITED";
    private static final String STATUS_INVALID = "INVALID";

    /** 账本 IN 查询分批大小，防单条 SQL 参数过多 */
    private static final int QUERY_CHUNK = 500;
    /** 差异落库分批大小 */
    private static final int INSERT_CHUNK = 500;

    private final ReconcileQueryMapper queryMapper;
    private final ReconcileDiffMapper diffMapper;
    private final ReconcileProperties reconcileProperties;
    private final DepositProperties depositProperties;
    private final TokenMetadataService tokenMetadataService;
    private final ReconcileShardExecutor shardExecutor;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReconcileReport run(LocalDate reportDate) {
        LocalDateTime from = reportDate.atStartOfDay();
        LocalDateTime to = from.plusDays(1);

        List<DepositRecord> records = queryMapper.selectDepositByCreatedAt(from, to);
        List<ChainOrphanRow> orphans = queryMapper.selectChainOrphans(from, to);
        Map<String, LedgerSummary> ledger = loadLedger(records);

        Set<String> whitelist = effectiveWhitelist();
        Map<String, Integer> currentDecimalsCache = new ConcurrentHashMap<>();

        // 按 user_id 分片并发分类；每条记录先过全局令牌桶（默认 50 QPS）
        List<ReconcileDiff> diffs = new ArrayList<>(
                classifySharded(reportDate, records, ledger, whitelist, currentDecimalsCache));
        for (ChainOrphanRow orphan : orphans) {
            diffs.add(classifyOrphan(reportDate, orphan, whitelist));
        }

        // 同一报告日重建：先删后插，重跑结果稳定
        diffMapper.deleteByDate(reportDate);
        for (int i = 0; i < diffs.size(); i += INSERT_CHUNK) {
            List<ReconcileDiff> chunk = diffs.subList(i, Math.min(i + INSERT_CHUNK, diffs.size()));
            diffMapper.insertBatch(chunk);
        }

        ReconcileReport report = summarize(reportDate, diffs);
        log.info("[reconcile] 完成 date={} records={} orphans={} total={} inTransit={} overShort={} undetermined={} netDelta={}",
                reportDate, records.size(), orphans.size(), report.total(), report.inTransit(),
                report.overShort(), report.undetermined(), report.netDelta().toPlainString());
        return report;
    }

    @Override
    public List<ReconcileDiff> list(LocalDate reportDate) {
        return diffMapper.selectByDate(reportDate);
    }

    // ------------------------------------------------------------------
    // 取数与聚合
    // ------------------------------------------------------------------

    private Map<String, LedgerSummary> loadLedger(List<DepositRecord> records) {
        List<String> bizNos = records.stream()
                .map(DepositRecord::getDepositNo)
                .distinct()
                .toList();
        Map<String, LedgerSummary> result = new HashMap<>();
        for (int i = 0; i < bizNos.size(); i += QUERY_CHUNK) {
            List<String> chunk = bizNos.subList(i, Math.min(i + QUERY_CHUNK, bizNos.size()));
            for (LedgerAggRow row : queryMapper.selectLedgerAgg(chunk)) {
                LedgerSummary summary = result.computeIfAbsent(row.getBizNo(), k -> new LedgerSummary());
                summary.entryCount++;
                if (row.getReversalCount() != null && row.getReversalCount() > 0) {
                    summary.anyReversed = true;
                } else {
                    summary.effectiveAmount = summary.effectiveAmount.add(nullToZero(row.getUserAmount()));
                }
            }
        }
        return result;
    }

    /**
     * 按 user_id 哈希分片并发分类：同一用户固定落在同一分片，线程数/限速由
     * {@link ReconcileShardExecutor} 统一控制。分类是纯计算，最后统一落库。
     */
    private List<ReconcileDiff> classifySharded(LocalDate date, List<DepositRecord> records,
                                                Map<String, LedgerSummary> ledger,
                                                Set<String> whitelist,
                                                Map<String, Integer> decimalsCache) {
        if (records.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<DepositRecord>> byShard = new HashMap<>();
        for (DepositRecord record : records) {
            byShard.computeIfAbsent(shardExecutor.shardOf(record.getUserId()),
                    key -> new ArrayList<>()).add(record);
        }

        List<CompletableFuture<List<ReconcileDiff>>> futures = new ArrayList<>(byShard.size());
        for (Map.Entry<Integer, List<DepositRecord>> entry : byShard.entrySet()) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                List<ReconcileDiff> part = new ArrayList<>();
                for (DepositRecord record : entry.getValue()) {
                    shardExecutor.acquire();
                    ReconcileDiff diff = classifyDeposit(date, record,
                            ledger.get(record.getDepositNo()), whitelist, decimalsCache);
                    if (diff != null) {
                        part.add(diff);
                    }
                }
                return part;
            }, shardExecutor.executor(entry.getKey())));
        }

        List<ReconcileDiff> result = new ArrayList<>(records.size());
        for (CompletableFuture<List<ReconcileDiff>> future : futures) {
            result.addAll(future.join());
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 六类判定
    // ------------------------------------------------------------------

    private ReconcileDiff classifyDeposit(LocalDate date, DepositRecord record, LedgerSummary ledger,
                                          Set<String> whitelist, Map<String, Integer> decimalsCache) {
        String depositNo = record.getDepositNo();
        String currency = record.getCurrency();
        BigDecimal chainAmount = parseAmount(record.getDisplayValue());

        if (STATUS_INVALID.equals(record.getStatus())) {
            // 链上已回滚：账本尚未冲正才需要报；已冲正视为已收敛
            if (ledger == null || ledger.effectiveAmount.signum() == 0) {
                return null;
            }
            return diff(date, depositNo, ReconcileConsts.DiffType.REORG,
                    ReconcileConsts.ReportGroup.IN_TRANSIT, null,
                    BigDecimal.ZERO, ledger.effectiveAmount, currency,
                    "链上已回滚，账本尚未冲正 entryCount=" + ledger.entryCount);
        }

        if (STATUS_PENDING.equals(record.getStatus())) {
            if (ledger == null || ledger.effectiveAmount.signum() == 0) {
                return diff(date, depositNo, ReconcileConsts.DiffType.TIMING,
                        ReconcileConsts.ReportGroup.IN_TRANSIT, null,
                        chainAmount, BigDecimal.ZERO, currency, "未达确认数，账本尚未入账");
            }
            return null;
        }

        if (!STATUS_CREDITED.equals(record.getStatus())) {
            return null;
        }

        if (ledger == null || ledger.entryCount == 0) {
            if (isBlank(currency)) {
                return diff(date, depositNo, ReconcileConsts.DiffType.UNKNOWN_TOKEN,
                        ReconcileConsts.ReportGroup.UNDETERMINED, null,
                        chainAmount, null, null, "币种未知，无法判断入账方向");
            }
            return diff(date, depositNo, ReconcileConsts.DiffType.MISSING,
                    ReconcileConsts.ReportGroup.OVER_SHORT, ReconcileConsts.Direction.SHORT,
                    chainAmount, BigDecimal.ZERO, currency, "已确认但账本无分录");
        }

        BigDecimal delta = ledger.effectiveAmount.subtract(chainAmount);

        if (ledger.entryCount > 1) {
            return diff(date, depositNo, ReconcileConsts.DiffType.DUPLICATE,
                    ReconcileConsts.ReportGroup.OVER_SHORT, directionOf(delta),
                    chainAmount, ledger.effectiveAmount, currency,
                    "同一业务键账本出现 " + ledger.entryCount + " 组分录");
        }

        if (delta.signum() == 0) {
            return null;
        }

        if (isPrecision(record, ledger.effectiveAmount, decimalsCache)) {
            int snapshot = record.getDecimals() == null ? -1 : record.getDecimals();
            int current = currentDecimals(record.getContractAddress(), snapshot, decimalsCache);
            return diff(date, depositNo, ReconcileConsts.DiffType.PRECISION,
                    ReconcileConsts.ReportGroup.OVER_SHORT, directionOf(delta),
                    chainAmount, ledger.effectiveAmount, currency,
                    "decimals snapshot=" + snapshot + " current=" + current);
        }

        // 六类兜底：金额差异无法用精度解释时，按方向归入遗漏/重复
        String fallbackType = delta.signum() < 0
                ? ReconcileConsts.DiffType.MISSING : ReconcileConsts.DiffType.DUPLICATE;
        return diff(date, depositNo, fallbackType, ReconcileConsts.ReportGroup.OVER_SHORT,
                directionOf(delta), chainAmount, ledger.effectiveAmount, currency,
                "金额差异，未命中精度规则");
    }

    private ReconcileDiff classifyOrphan(LocalDate date, ChainOrphanRow orphan, Set<String> whitelist) {
        String contract = lower(orphan.getContractAddress());
        String bizNo = ReconcileConsts.CHAIN_ORPHAN_PREFIX
                + orphan.getTransactionHash() + ":" + orphan.getLogIndex();
        BigDecimal chainAmount = parseAmount(orphan.getDisplayValue());
        String currency = depositProperties.getTokenSymbols().get(contract);

        if (!whitelist.isEmpty() && !whitelist.contains(contract)) {
            return diff(date, bizNo, ReconcileConsts.DiffType.UNKNOWN_TOKEN,
                    ReconcileConsts.ReportGroup.UNDETERMINED, null,
                    chainAmount, null, null, "合约不在代币白名单: " + contract);
        }
        return diff(date, bizNo, ReconcileConsts.DiffType.MISSING,
                ReconcileConsts.ReportGroup.OVER_SHORT, ReconcileConsts.Direction.SHORT,
                chainAmount, BigDecimal.ZERO, currency, "链上有事件但无充值记录");
    }

    // ------------------------------------------------------------------
    // 精度差
    // ------------------------------------------------------------------

    /**
     * 判定是否精度差：当前 decimals 与快照不同，且按当前值重算的金额等于账本金额。
     * <p>
     * 例：快照 decimals=6、raw=10_000_000 → 链上 10；当前 decimals=8 时重算为 0.1，
     * 若账本正好记 0.1，说明账本是按当前精度重算的，差额来自精度口径而不是漏记。
     */
    private boolean isPrecision(DepositRecord record, BigDecimal ledgerAmount,
                                Map<String, Integer> decimalsCache) {
        if (record.getDecimals() == null || isBlank(record.getRawValue())) {
            return false;
        }
        int snapshot = record.getDecimals();
        int current = currentDecimals(record.getContractAddress(), snapshot, decimalsCache);
        if (current == snapshot) {
            return false;
        }
        try {
            BigDecimal atCurrent = MoneyUtils.toDisplay(new BigInteger(record.getRawValue()), current);
            return atCurrent.compareTo(ledgerAmount) == 0;
        } catch (NumberFormatException e) {
            log.warn("[reconcile] raw_value 非数字，跳过精度判定 depositNo={}", record.getDepositNo());
            return false;
        }
    }

    private int currentDecimals(String contract, int snapshot, Map<String, Integer> cache) {
        String key = lower(contract);
        Integer cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        int current;
        Integer override = reconcileProperties.getCurrentDecimals().get(key);
        if (override != null) {
            current = override;
        } else {
            try {
                current = tokenMetadataService.decimals(contract);
            } catch (Exception e) {
                // 链上取不到就退回快照，宁可判不出精度差，也不要误判
                current = snapshot;
            }
        }
        cache.put(key, current);
        return current;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private Set<String> effectiveWhitelist() {
        Set<String> result = new HashSet<>();
        for (String contract : reconcileProperties.getTokenWhitelist()) {
            if (contract != null && !contract.isBlank()) {
                result.add(lower(contract));
            }
        }
        if (result.isEmpty()) {
            // 未显式配置时回退到充值侧 token-symbols 的合约集合
            result.addAll(depositProperties.getTokenSymbols().keySet());
        }
        return result;
    }

    private ReconcileDiff diff(LocalDate date, String bizNo, String diffType, String group,
                               String direction, BigDecimal chainAmount, BigDecimal ledgerAmount,
                               String currency, String evidence) {
        ReconcileDiff diff = new ReconcileDiff();
        diff.setReportDate(date);
        diff.setBizType("DEPOSIT");
        diff.setBizNo(bizNo);
        diff.setDiffType(diffType);
        diff.setReportGroup(group);
        diff.setDirection(direction);
        diff.setChainAmount(chainAmount == null ? null : MoneyUtils.normalize(chainAmount));
        diff.setLedgerAmount(ledgerAmount == null ? null : MoneyUtils.normalize(ledgerAmount));
        if (chainAmount != null && ledgerAmount != null) {
            diff.setDelta(MoneyUtils.normalize(ledgerAmount.subtract(chainAmount)));
        }
        diff.setCurrency(currency);
        diff.setEvidence(evidence);
        diff.setStatus(ReconcileConsts.Status.OPEN);
        return diff;
    }

    private static String directionOf(BigDecimal delta) {
        return delta.signum() >= 0
                ? ReconcileConsts.Direction.LONG
                : ReconcileConsts.Direction.SHORT;
    }

    private static BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return MoneyUtils.normalize(new BigDecimal(value));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private ReconcileReport summarize(LocalDate date, List<ReconcileDiff> diffs) {
        int inTransit = 0;
        int overShort = 0;
        int undetermined = 0;
        BigDecimal net = BigDecimal.ZERO;
        for (ReconcileDiff diff : diffs) {
            switch (diff.getReportGroup()) {
                case ReconcileConsts.ReportGroup.IN_TRANSIT -> inTransit++;
                case ReconcileConsts.ReportGroup.OVER_SHORT -> overShort++;
                default -> undetermined++;
            }
            if (diff.getDelta() != null) {
                net = net.add(diff.getDelta());
            }
        }
        return new ReconcileReport(date, diffs.size(), inTransit, overShort, undetermined,
                MoneyUtils.normalize(net));
    }

    /** 账本侧按业务键聚合 */
    private static final class LedgerSummary {
        private int entryCount;
        private BigDecimal effectiveAmount = BigDecimal.ZERO;
        private boolean anyReversed;
    }
}
