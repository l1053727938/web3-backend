package com.ray.web3.scanner;

import com.ray.web3.config.DepositProperties;
import com.ray.web3.service.DepositReorgService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 充值重组回滚扫描器（第 5 周 Day 5-6）
 * <p>
 * 默认关闭（{@code web3.deposit.reorg-enabled=false}）；Reorg 演练时打开。
 * 只检查最近窗口内已确认的充值，避免全表回扫。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "web3.deposit.enabled", havingValue = "true", matchIfMissing = true)
public class DepositReorgScanner {

    private final DepositReorgService depositReorgService;
    private final DepositProperties depositProperties;

    @Scheduled(fixedDelayString = "${web3.deposit.reorg-interval-ms:60000}",
            initialDelayString = "${web3.deposit.reorg-interval-ms:60000}")
    public void scan() {
        if (!depositProperties.isReorgEnabled()) {
            return;
        }
        try {
            depositReorgService.scanRecentReorgs();
        } catch (Exception e) {
            log.error("[deposit-reorg] 扫描异常，下一轮重试: {}", e.getMessage(), e);
        }
    }
}
