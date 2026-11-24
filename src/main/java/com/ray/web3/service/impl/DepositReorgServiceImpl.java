package com.ray.web3.service.impl;

import com.ray.web3.config.DepositProperties;
import com.ray.web3.config.InfuraProperties;
import com.ray.web3.consts.DepositConsts;
import com.ray.web3.exception.LedgerPostingException;
import com.ray.web3.mapper.DepositRecordMapper;
import com.ray.web3.model.entity.DepositRecord;
import com.ray.web3.model.entity.LedgerEntry;
import com.ray.web3.service.DepositReorgService;
import com.ray.web3.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.util.List;

/**
 * 充值重组回滚实现（第 5 周 Day 5-6）
 * <p>
 * 判定“日志消失”以 receipt 为准：
 * <ul>
 *   <li>receipt 为空 → 交易已不在规范链；</li>
 *   <li>receipt 存在但找不到对应 logIndex → 该日志被重组掉。</li>
 * </ul>
 * RPC 异常不当作重组，直接跳过等下一轮（宁可晚回滚，不可误回滚）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "web3.deposit.enabled", havingValue = "true", matchIfMissing = true)
public class DepositReorgServiceImpl implements DepositReorgService {

    private final DepositRecordMapper depositRecordMapper;
    private final LedgerService ledgerService;
    private final DepositProperties depositProperties;
    private final InfuraProperties infuraProperties;
    private final Web3j web3j;

    @Override
    public boolean markReorged(String depositNo) {
        DepositRecord record = depositRecordMapper.selectByDepositNo(depositNo);
        if (record == null) {
            return false;
        }
        boolean credited = DepositConsts.Status.CREDITED.equals(record.getStatus());
        if (!credited && !DepositConsts.Status.PENDING_CONFIRM.equals(record.getStatus())) {
            return false;
        }

        // 先冲正再置 INVALID：冲正失败（如账本抖动）时保留状态，下一轮重试；
        // 冲正本身按 (REVERSE, REV:entryNo) 幂等，重复执行不会多冲。
        if (credited) {
            try {
                LedgerEntry reversal = ledgerService.reverse(
                        com.ray.web3.consts.LedgerConsts.BizType.DEPOSIT, depositNo);
                log.warn("[deposit-reorg] 已写冲正分录 depositNo={} reversalEntry={}",
                        depositNo, reversal.getEntryNo());
            } catch (LedgerPostingException e) {
                // 该笔从未落账本（例如消费失败），没有可冲正的分录
                log.warn("[deposit-reorg] 无账本分录可冲正 depositNo={}: {}", depositNo, e.getMessage());
            }
        }

        int updated = depositRecordMapper.markInvalid(depositNo);
        if (updated > 0) {
            log.warn("[deposit-reorg] 充值已回滚 depositNo={} tx={} logIndex={} block={}",
                    depositNo, record.getTransactionHash(), record.getLogIndex(), record.getBlockNumber());
            return true;
        }
        return false;
    }

    @Override
    public int scanRecentReorgs() {
        if (!depositProperties.isReorgEnabled()) {
            return 0;
        }
        try {
            long head = web3j.ethBlockNumber().send().getBlockNumber().longValue();
            long minBlock = Math.max(0L, head - depositProperties.getReorgLookbackBlocks());
            List<DepositRecord> records = depositRecordMapper.selectCreditedSince(
                    infuraProperties.getChainId(), minBlock,
                    Math.max(1, depositProperties.getReorgBatchSize()));

            int rolledBack = 0;
            for (DepositRecord record : records) {
                try {
                    if (logMissing(record) && markReorged(record.getDepositNo())) {
                        rolledBack++;
                    }
                } catch (Exception e) {
                    // 节点抖动/超时：不判重组，下一轮再查
                    log.warn("[deposit-reorg] 校验失败，跳过 depositNo={}: {}",
                            record.getDepositNo(), e.getMessage());
                }
            }
            if (rolledBack > 0) {
                log.warn("[deposit-reorg] 本轮发现并回滚 {} 笔充值", rolledBack);
            }
            return rolledBack;
        } catch (Exception e) {
            log.error("[deposit-reorg] 扫描异常: {}", e.getMessage(), e);
            return 0;
        }
    }

    private boolean logMissing(DepositRecord record) throws Exception {
        TransactionReceipt receipt = web3j.ethGetTransactionReceipt(record.getTransactionHash())
                .send().getTransactionReceipt().orElse(null);
        if (receipt == null) {
            return true;
        }
        List<Log> logs = receipt.getLogs();
        if (logs == null) {
            return true;
        }
        BigInteger expected = record.getLogIndex() == null
                ? null : BigInteger.valueOf(record.getLogIndex());
        return expected != null && logs.stream()
                .noneMatch(log -> log.getLogIndex() != null
                        && log.getLogIndex().equals(expected));
    }
}
