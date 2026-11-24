package com.ray.web3.service.impl;

import com.google.common.util.concurrent.RateLimiter;
import com.ray.web3.config.ReconcileProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 对账分片执行器（第 5 周 Day 5-6）
 * <p>
 * 按 {@code user_id} 哈希分 N 片（默认 8），每片独立线程池（core=2）；
 * 所有分片共享一个 Guava 令牌桶（默认 50 QPS），把对从库的压力压平。
 * <p>
 * 分片只影响“谁在哪个线程上算”，不影响幂等：分类是纯计算，结果最后统一落库。
 */
@Slf4j
@Component
public class ReconcileShardExecutor {

    private final ExecutorService[] shards;
    private final RateLimiter rateLimiter;

    public ReconcileShardExecutor(ReconcileProperties properties) {
        int shardCount = Math.max(1, properties.getShards());
        int threads = Math.max(1, properties.getShardThreads());
        this.shards = new ExecutorService[shardCount];
        for (int i = 0; i < shardCount; i++) {
            final int shardIndex = i;
            this.shards[i] = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable, "reconcile-shard-" + shardIndex);
                thread.setDaemon(true);
                return thread;
            });
        }
        this.rateLimiter = RateLimiter.create(Math.max(1, properties.getQps()));
        log.info("[reconcile] 分片执行器就绪 shards={} threadsPerShard={} qps={}",
                shardCount, threads, properties.getQps());
    }

    /** user_id 哈希确定分片；同一用户永远落在同一片 */
    public int shardOf(String userId) {
        int hash = userId == null ? 0 : userId.hashCode();
        return Math.floorMod(hash, shards.length);
    }

    public ExecutorService executor(int shard) {
        return shards[Math.floorMod(shard, shards.length)];
    }

    /** 全局限流：处理每条记录前获取一个许可 */
    public void acquire() {
        rateLimiter.acquire();
    }

    @PreDestroy
    public void close() {
        for (ExecutorService shard : shards) {
            shard.shutdown();
            try {
                if (!shard.awaitTermination(5, TimeUnit.SECONDS)) {
                    shard.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                shard.shutdownNow();
            }
        }
    }
}
