package com.ray.web3.scanner;

import com.ray.web3.service.WithdrawRefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 提币失败补偿扫描器（第 4 周 Day 3-4）
 * <p>
 * 周期驱动 {@link WithdrawRefundService}，把 FAILED 且未补偿的提币自动解冻。
 * 不加分布式锁：补偿本身是幂等的（账本 bizNo 唯一键 + refund_status 条件更新），
 * 多实例同时跑最多是重复查询，不会重复加钱。
 * <p>
 * 异常只记录不抛出，避免一次账本抖动中断调度线程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "web3.withdraw", name = "enabled", havingValue = "true")
public class WithdrawRefundScanner {

    private final WithdrawRefundService withdrawRefundService;

    @Scheduled(fixedDelayString = "${web3.withdraw.refund-scan-interval-ms:60000}",
            initialDelayString = "${web3.withdraw.refund-scan-initial-delay-ms:30000}")
    public void scan() {
        try {
            int refunded = withdrawRefundService.refundFailedWithdrawals();
            if (refunded > 0) {
                log.info("[withdraw-refund] 定时补偿完成 {} 笔", refunded);
            }
        } catch (Exception e) {
            log.error("[withdraw-refund] 扫描异常，下一轮重试: {}", e.getMessage(), e);
        }
    }
}
