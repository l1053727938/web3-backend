package com.ray.web3.scanner;

import com.ray.web3.config.WithdrawProperties;
import com.ray.web3.service.WithdrawTrackingService;
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
 * 提币广播后状态追踪扫描器（第 3 周 Day 3-4：广播与状态追踪）
 * <p>
 * 业务定位：提币广播成功只到 {@code BROADCAST}（进内存池、结果未定），本扫描器周期驱动
 * {@link WithdrawTrackingService} 轮询 {@code eth_getTransactionReceipt}，把已确认/已 Revert 的交易推进到终态，
 * 补上"发出去之后到底成没成"这一环。触发通道与 {@link AutoBackfillScanner} 同款双通道：
 * Spring Scheduler（默认，单实例进程内定时）+ XXL-Job（接入调度中心后分布式统一触发）。
 * <p>
 * 关键设计：
 * 1. 全局 Redisson 锁 tryLock：多实例仅一个节点执行本轮；抢不到直接让位下一轮
 *    （追踪本身是幂等的条件 UPDATE，让位只是省掉重复 RPC，不是正确性依赖）；
 * 2. 追踪服务受 web3.withdraw.enabled 门控，用 ObjectProvider 延迟取得，关闭时本器空转不报错；
 * 3. 每轮异常只记录不抛出，绝不让一次链路抖动中断调度线程；
 * 4. track-enabled=false 时停自动推进（仍可手动查询接口观测），与广播能力解耦便于灰度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "web3.withdraw", name = "enabled", havingValue = "true")
public class WithdrawStatusTracker {

    private static final String LOCK_KEY = "web3:withdraw:track:lock";

    /** 追踪服务受 web3.withdraw.enabled 控制，关闭时 getIfAvailable()=null，本器跳过 */
    private final ObjectProvider<WithdrawTrackingService> withdrawTrackingService;
    private final RedissonClient redissonClient;
    private final WithdrawProperties withdrawProperties;

    @Scheduled(fixedDelayString = "${web3.withdraw.track-interval-ms:15000}")
    public void trackByScheduler() {
        doTrack("spring-scheduler");
    }

    @XxlJob("withdrawTrackHandler")
    public void trackByXxlJob() {
        doTrack("xxl-job");
    }

    private void doTrack(String trigger) {
        if (!withdrawProperties.isTrackEnabled()) {
            log.debug("[{}] 提币状态追踪已关闭（web3.withdraw.track-enabled=false），跳过", trigger);
            return;
        }
        WithdrawTrackingService service = withdrawTrackingService.getIfAvailable();
        if (service == null) {
            log.debug("[{}] 提币追踪服务未启用（web3.withdraw.enabled=false），跳过", trigger);
            return;
        }
        RLock lock = redissonClient.getLock(LOCK_KEY);
        if (!lock.tryLock()) {
            log.debug("[{}] 其他实例正在追踪提币状态，跳过本轮", trigger);
            return;
        }
        try {
            int transitions = service.trackPendingBroadcasts();
            if (transitions > 0) {
                log.info("[{}] 提币状态追踪本轮跃迁 {} 笔至终态", trigger, transitions);
            }
        } catch (Exception e) {
            log.warn("[{}] 提币状态追踪轮次失败（下一轮自动重试）: {}", trigger, e.getMessage());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
