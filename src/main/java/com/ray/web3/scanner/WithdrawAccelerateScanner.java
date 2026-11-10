package com.ray.web3.scanner;

import com.ray.web3.config.WithdrawProperties;
import com.ray.web3.service.WithdrawAccelerateService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 提币卡单自动加速扫描器（第 3 周 Day 5-7：Replace-by-fee）
 * <p>
 * 业务定位：追踪扫描器（{@link WithdrawStatusTracker}）负责"把有结果的交易推成终态 + 标注卡单"，
 * 本器负责"把没结果的卡单重新推上轨道"——同 Nonce 提价重播。两者节奏必须分开：追踪 15s 一轮够用，
 * 加速过快会把 {@code accelerate-max-count} 的预算在几分钟内烧完，反而更早落到人工手里。
 * <p>
 * 触发通道与 {@link AutoBackfillScanner}/{@link WithdrawStatusTracker} 同款双通道：
 * Spring Scheduler（默认，单实例进程内定时）+ XXL-Job（接入调度中心后分布式统一触发），
 * 各自一把独立锁（不与追踪共用）：追踪是只读推进、加速要发交易，共用锁会让广播被收据轮询的节奏拖住
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "web3.withdraw", name = "enabled", havingValue = "true")
public class WithdrawAccelerateScanner {

    private static final String LOCK_KEY = "web3:withdraw:accelerate:lock";

    /** 加速服务受 web3.withdraw.enabled 控制，关闭时 getIfAvailable()=null，本器跳过 */
    private final ObjectProvider<WithdrawAccelerateService> withdrawAccelerateService;
    private final RedissonClient redissonClient;
    private final WithdrawProperties withdrawProperties;

//    @Scheduled(fixedDelayString = "${web3.withdraw.accelerate-interval-ms:60000}")
    public void accelerateByScheduler() {
        doAccelerate("spring-scheduler");
    }

    @XxlJob("withdrawAccelerateHandler")
    public void accelerateByXxlJob() {
        doAccelerate("xxl-job");
    }

    private void doAccelerate(String trigger) {
        if (!withdrawProperties.isAccelerateEnabled()) {
            log.debug("[{}] 提币卡单自动加速已关闭（web3.withdraw.accelerate-enabled=false），跳过", trigger);
            return;
        }
        WithdrawAccelerateService service = withdrawAccelerateService.getIfAvailable();
        if (service == null) {
            log.debug("[{}] 提币加速服务未启用（web3.withdraw.enabled=false），跳过", trigger);
            return;
        }
        RLock lock = redissonClient.getLock(LOCK_KEY);
        if (!lock.tryLock()) {
            log.debug("[{}] 其他实例正在处置提币卡单，跳过本轮", trigger);
            return;
        }
        try {
            int acted = service.accelerateStuckBroadcasts();
            if (acted > 0) {
                log.info("[{}] 提币卡单处置本轮对 {} 笔采取行动（重播/标注转人工）", trigger, acted);
            }
        } catch (Exception e) {
            log.warn("[{}] 提币卡单处置轮次失败（下一轮自动重试）: {}", trigger, e.getMessage());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
