package com.ray.web3.scanner;

import com.ray.web3.config.WithdrawProperties;
import com.ray.web3.service.WithdrawInitRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * INIT 提币接管扫描器（第 5 周 Day 5-6）
 * <p>
 * 周期扫描 INIT：已上链的成功/失败落终态，其余保持 INIT。
 * 与 {@code WithdrawStatusTracker}（只扫 BROADCAST）互补：INIT 过去从未被接管。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "web3.withdraw", name = "enabled", havingValue = "true")
public class WithdrawInitRecoveryScanner {

    private final WithdrawInitRecoveryService withdrawInitRecoveryService;
    private final WithdrawProperties withdrawProperties;

    @Scheduled(fixedDelayString = "${web3.withdraw.init-recovery-interval-ms:60000}",
            initialDelayString = "${web3.withdraw.init-recovery-interval-ms:60000}")
    public void scan() {
        if (!withdrawProperties.isInitRecoveryEnabled()) {
            return;
        }
        try {
            withdrawInitRecoveryService.recoverOnce();
        } catch (Exception e) {
            log.error("[withdraw-init] 扫描异常，下一轮重试: {}", e.getMessage(), e);
        }
    }
}
