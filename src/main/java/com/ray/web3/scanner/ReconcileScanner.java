package com.ray.web3.scanner;

import com.ray.web3.service.ReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 每日对账触发器（第 5 周 Day 3-4）
 * <p>
 * 默认关闭；打开后按 cron 跑**前一天**的窗口（当天数据还在增长，跑当天没有意义）。
 * 异常只记录不抛出，不能因为一次对账失败影响其他调度任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "web3.reconcile", name = "enabled", havingValue = "true")
public class ReconcileScanner {

    private final ReconcileService reconcileService;

    @Scheduled(cron = "${web3.reconcile.cron:0 30 1 * * *}")
    public void runDaily() {
        LocalDate date = LocalDate.now().minusDays(1);
        try {
            reconcileService.run(date);
        } catch (Exception e) {
            log.error("[reconcile] 每日对账失败 date={}: {}", date, e.getMessage(), e);
        }
    }
}
