package com.ray.web3.ratelimit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import com.ray.web3.config.AuthProperties;
import com.ray.web3.util.BoundedQueueRateLimiter;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单机守卫（LOCAL）：全部用进程内原语，零外部依赖。
 * <p>
 * 闸门顺序与实现：
 * <ol>
 *   <li><b>全局速率</b>：Guava 令牌桶一份（本实例口径）。多副本部署时总吞吐 = 单机速率 × 副本数，
 *       需要全集群统一口径请切 DISTRIBUTED 模式。</li>
 *   <li><b>每用户速率</b>：Guava Cache 惰性建桶（{@code expireAfterAccess} 自动回收闲置用户，防 key 泄漏）。</li>
 *   <li><b>每用户日总量配额</b>：Guava Cache 存 AtomicInteger，key=identity，{@code expireAfterWrite 25h}
 *       兜底跨天后重建 → 语义为“每个用户每日滚动计数”，超限回退计数（被拒请求不消耗配额，与分布式口径一致）。</li>
 *   <li><b>并发槽位</b>：{@link BoundedQueueRateLimiter}；FAST_FAIL 时队列容量与超时按 0 构造
 *       （退化为“有槽就过、没槽就拒”），BOUNDED_QUEUE 时按配置排队。</li>
 * </ol>
 * 速率闸门一律<b>不等待</b>（严格到达上限），排队预算只花在并发槽位上——总阻塞时长有界（≤ timeoutMs），
 * 不会出现“两层各等一份超时”的叠加
 */
public class LocalRateLimitGuard implements RateLimitGuard {

    private final AuthProperties.RateLimit config;
    private final RateLimiter globalBucket;
    /** 每用户速率桶：闲置 10 分钟自动回收；容量兜底防极端 key 膨胀 */
    private final Cache<String, RateLimiter> userBuckets;
    /** 每用户当日调用计数：跨天由 expireAfterWrite 淘汰重建（TTL 略大于 1 天，日界由 key 前缀保障） */
    private final Cache<String, AtomicInteger> userDailyCounters;
    private final BoundedQueueRateLimiter concurrencyGate;
    /** 拒绝时回给调用方的 Retry-After 建议值（速率闸门按平均令牌间隔估算） */
    private final long retryAfterMs;

    public LocalRateLimitGuard(AuthProperties.RateLimit config) {
        this.config = config;
        this.globalBucket = RateLimiter.create(Math.max(config.getPermitsPerSecond(), 0.0001));
        this.userBuckets = CacheBuilder.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(10))
                .maximumSize(100_000L)
                .build();
        this.userDailyCounters = CacheBuilder.newBuilder()
                .expireAfterWrite(Duration.ofHours(25))
                .maximumSize(100_000L)
                .build();
        boolean bounded = config.getQueuePolicy() == AuthProperties.RateLimit.QueuePolicy.BOUNDED_QUEUE;
        this.concurrencyGate = new BoundedQueueRateLimiter(
                Math.max(config.getMaxConcurrent(), 1),
                bounded ? Math.max(config.getQueueCapacity(), 0) : 0,
                bounded ? Math.max(config.getTimeoutMs(), 0) : 0);
        this.retryAfterMs = Math.max(50L, (long) (1000 / Math.max(config.getPermitsPerSecond(), 0.0001)));
    }

    @Override
    public RateLimitDecision check(String identity) throws InterruptedException {
        // ① 全局速率：拿不到令牌立即拒（速率层不排队，见类注释）
        if (config.getPermitsPerSecond() > 0 && !globalBucket.tryAcquire(1, 0, TimeUnit.MILLISECONDS)) {
            return RateLimitDecision.rateLimited("全局请求速率超限", retryAfterMs);
        }

        // ② 每用户速率：防单个大户独占全局额度
        if (config.isPerUserEnabled() && config.getUserPermitsPerSecond() > 0) {
            RateLimiter userBucket;
            try {
                userBucket = userBuckets.get(identity, () -> RateLimiter.create(config.getUserPermitsPerSecond()));
            } catch (ExecutionException e) {
                throw new IllegalStateException("每用户速率桶创建失败", e);
            }
            if (!userBucket.tryAcquire(1, 0, TimeUnit.MILLISECONDS)) {
                return RateLimitDecision.rateLimited("该调用方速率超限", retryAfterMs);
            }
        }

        // ③ 每用户日总量配额：与速率无关的累计上限，超限拒绝（配额闸门排在并发闸门之前，不占用槽位）
        if (config.isQuotaEnabled() && config.getUserDailyQuota() > 0) {
            AtomicInteger counter;
            try {
                counter = userDailyCounters.get(identity, AtomicInteger::new);
            } catch (ExecutionException e) {
                throw new IllegalStateException("用户日计数缓存创建失败", e);
            }
            int used = counter.incrementAndGet();
            if (used > config.getUserDailyQuota()) {
                // 与分布式 Lua 同口径：被拒请求回退计数，不消耗配额
                counter.decrementAndGet();
                return RateLimitDecision.quotaExceeded("今日配额已用尽（上限 " + config.getUserDailyQuota() + " 次）");
            }
        }

        // ④ 并发槽位：FAST_FAIL 立即判定；BOUNDED_QUEUE 允许最多 queueCapacity 人排队、每队最长 timeoutMs
        if (config.getMaxConcurrent() > 0) {
            Runnable release = concurrencyGate.tryAcquire();
            if (release == null) {
                return RateLimitDecision.rateLimited("系统并发已满，排队区也已排满", retryAfterMs);
            }
            return RateLimitDecision.allow(release);
        }
        return RateLimitDecision.allow(RateLimitDecision.noop());
    }
}
