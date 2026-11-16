package com.ray.web3.scanner;

import com.ray.web3.service.impl.OutboxDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 定时投递触发器（第 3 周 Day 3-4）
 * <p>
 * 只负责按固定间隔调用 {@link OutboxDispatcher#dispatchBatch()}；
 * 事务边界在 Dispatcher 上（经 Spring 代理调用，{@code @Transactional} 生效）。
 * <p>
 * 多实例不需要分布式锁：投递器用 {@code SELECT ... FOR UPDATE SKIP LOCKED} 抢批次，
 * 数据库行锁本身就是防重入机制；某个实例宕机，未提交的事务回滚，消息仍是 NEW，下一轮被其他实例接走。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "web3.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatchScheduler {

    private final OutboxDispatcher outboxDispatcher;

    @Scheduled(fixedDelayString = "${web3.outbox.dispatch-interval-ms:1000}")
    public void dispatch() {
        try {
            int sent = outboxDispatcher.dispatchBatch();
            if (sent > 0) {
                log.debug("[outbox] 定时投递完成 sent={}", sent);
            }
        } catch (Exception e) {
            // 本轮失败不终止调度：未提交的消息仍是 NEW，下一轮自动重试
            log.error("[outbox] 投递轮次异常，下一轮重新扫描: {}", e.getMessage(), e);
        }
    }
}