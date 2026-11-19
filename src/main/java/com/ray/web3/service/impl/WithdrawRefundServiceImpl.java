package com.ray.web3.service.impl;

import com.ray.web3.config.WithdrawProperties;
import com.ray.web3.mapper.WithdrawRecordMapper;
import com.ray.web3.model.entity.LedgerEntry;
import com.ray.web3.model.entity.WithdrawRecord;
import com.ray.web3.service.LedgerService;
import com.ray.web3.service.WithdrawRefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 提币失败补偿实现（第 4 周 Day 3-4）
 * <p>
 * 幂等分三层：
 * <ol>
 *   <li>扫描条件 {@code refund_status='NONE'}，已补偿的不再进入批次；</li>
 *   <li>账本解冻单号固定为 {@code withdrawNo + "_REFUND"}，重复调用撞 {@code uk_biz} 返回原分录；</li>
 *   <li>补偿成功后才置 REFUNDED；若账本调用失败，状态保持 NONE，下一轮重试。</li>
 * </ol>
 * 顺序不能反：先置 REFUNDED 再解冻，一旦解冻失败就再也不会重试，用户资金永久冻结。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "web3.withdraw", name = "enabled", havingValue = "true")
public class WithdrawRefundServiceImpl implements WithdrawRefundService {

    private final WithdrawRecordMapper withdrawRecordMapper;
    private final LedgerService ledgerService;
    private final WithdrawProperties withdrawProperties;

    @Override
    public int refundFailedWithdrawals() {
        int batchSize = Math.max(1, withdrawProperties.getRefundBatchSize());
        List<WithdrawRecord> batch = withdrawRecordMapper.selectFailedUnrefunded(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        int refunded = 0;
        for (WithdrawRecord record : batch) {
            try {
                LedgerEntry entry = ledgerService.refundWithdrawFreeze(record.getWithdrawNo());
                withdrawRecordMapper.markRefunded(record.getWithdrawNo());
                refunded++;
                log.info("[withdraw-refund] 解冻补偿完成 withdrawNo={} ledgerEntry={}",
                        record.getWithdrawNo(),
                        entry == null ? "NONE(无冻结分录)" : entry.getEntryNo());
            } catch (Exception e) {
                // 保持 refund_status=NONE，下一轮重试；账本按 bizNo 幂等，不会重复解冻
                log.error("[withdraw-refund] 解冻补偿失败，保留待重试 withdrawNo={} status={}: {}",
                        record.getWithdrawNo(), record.getStatus(), e.getMessage(), e);
            }
        }
        log.info("[withdraw-refund] 本轮待补偿 {} 条，完成 {} 条", batch.size(), refunded);
        return refunded;
    }
}
