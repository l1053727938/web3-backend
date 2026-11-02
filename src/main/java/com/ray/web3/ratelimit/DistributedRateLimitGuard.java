package com.ray.web3.ratelimit;

import com.ray.web3.config.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 分布式守卫（DISTRIBUTED）：多实例部署时所有副本共用同一 Redis 状态，限流口径是<b>全集群合计</b>。
 * <p>
 * 四道闸与实现选择：
 * <ol>
 *   <li><b>全局速率</b>：Redisson {@code RRateLimiter}（{@code RateType.OVERALL}），全集群令牌桶，不等待快速失败。</li>
 *   <li><b>每用户速率</b>：固定窗口计数器（1 秒桶 + Lua 原子 INCR/首次设 TTL），key 自带 TTL 自动回收；
 *       不用 RRateLimiter 按用户建桶，避免海量 Redis 对象与无 TTL 的状态残留。</li>
 *   <li><b>每用户日总量配额</b>：同上，日桶 key {@code quota:{identity}:{yyyyMMdd}}，Lua 里“超限则回退计数并拒绝”，
 *       保证被拒请求不占用配额。</li>
 *   <li><b>并发槽位</b>：{@code RPermitExpirableSemaphore}（带租期许可）。FAST_FAIL 等待 0ms；
 *       BOUNDED_QUEUE 等待 timeoutMs —— 排队预算由“信号量等待超时 + 调用方线程池上界”双重有界，
 *       不会无限堆积。许可带租期：实例崩溃未归还时到期自动回收，防槽位泄漏。</li>
 * </ol>
 * <p>
 * 已知边界：① Redis 故障时选择<b>降级放行</b>（限流组件不反过来打死业务，只告警）；
 * ② 信号量/限流器的 permits、rate 在 Redis 中持久化，<b>修改配置后需删除对应 Redis key 或换新 key 才生效</b>
 * （Redisson 的 trySetPermits/trySetRate 对已存在的配置不覆盖）
 */
@Slf4j
public class DistributedRateLimitGuard implements RateLimitGuard {

    /** 固定窗口速率计数 Lua：INCR + 首次设 TTL；超限返回 -1，否则返回当前计数 */
    private static final String RATE_WINDOW_LUA = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            if count > tonumber(ARGV[1]) then return -1 end
            return count""";

    /** 日配额计数 Lua：同上，但超限回退本次计数（拒绝的请求不消耗配额） */
    private static final String QUOTA_LUA = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            if count > tonumber(ARGV[1]) then
                redis.call('DECR', KEYS[1])
                return -1
            end
            return count""";

    private final AuthProperties.RateLimit config;
    private final RedissonClient redisson;

    private final RRateLimiter globalLimiter;
    private final RPermitExpirableSemaphore concurrencySemaphore;
    /** 信号量与全局限流器需在启动时一次性设容量/速率；已存在配置不覆盖（见类注释已知边界②） */
    private final String semaphoreName;

    public DistributedRateLimitGuard(AuthProperties.RateLimit config, RedissonClient redisson) {
        this.config = config;
        this.redisson = redisson;

        this.globalLimiter = redisson.getRateLimiter("web3:rl:global:chain-query");
        this.globalLimiter.trySetRate(RateType.OVERALL,
                Math.max(1, (long) Math.ceil(config.getPermitsPerSecond())),
                Duration.ofSeconds(1));

        this.semaphoreName = "web3:rl:concurrency:chain-query:" + config.getMaxConcurrent();
        this.concurrencySemaphore = redisson.getPermitExpirableSemaphore(semaphoreName);
        if (config.getMaxConcurrent() > 0) {
            this.concurrencySemaphore.trySetPermits(config.getMaxConcurrent());
        }
    }

    @Override
    public RateLimitDecision check(String identity) throws InterruptedException {
        // ① 全局速率：集群令牌桶，不等待
        if (config.getPermitsPerSecond() > 0
                && !globalLimiter.tryAcquire(1, 0, TimeUnit.MILLISECONDS)) {
            return RateLimitDecision.rateLimited("集群全局速率超限",
                    Math.max(50L, 1000 / Math.max(1, (long) config.getPermitsPerSecond())));
        }

        // ② 每用户速率：1 秒固定窗口，超限拒绝
        if (config.isPerUserEnabled() && config.getUserPermitsPerSecond() > 0) {
            int limit = Math.max(1, (int) Math.ceil(config.getUserPermitsPerSecond()));
            long second = System.currentTimeMillis() / 1000;
            String key = "web3:rl:user:" + identity + ":" + second;
            Long r = evalCounter(RATE_WINDOW_LUA, key, limit, 2000L);
            if (r != null && r == -1L) {
                return RateLimitDecision.rateLimited("该调用方速率超限（" + limit + " 次/秒）", 200L);
            }
        }

        // ③ 每用户日总量配额：日键 TTL 到次日过期，超限则 Lua 内回退计数不消耗额度
        if (config.isQuotaEnabled() && config.getUserDailyQuota() > 0) {
            String day = LocalDate.now(ZoneOffset.UTC).toString();
            String key = "web3:quota:user:" + identity + ":d" + day;
            Long r = evalCounter(QUOTA_LUA, key, config.getUserDailyQuota(), 90_000L);
            if (r != null && r == -1L) {
                return RateLimitDecision.quotaExceeded("今日配额已用尽（上限 " + config.getUserDailyQuota() + " 次）");
            }
        }

        // ④ 并发槽位（集群合计）：FAST_FAIL 不等待；BOUNDED_QUEUE 最多等 timeoutMs
        if (config.getMaxConcurrent() > 0) {
            boolean bounded = config.getQueuePolicy() == AuthProperties.RateLimit.QueuePolicy.BOUNDED_QUEUE;
            long waitMs = bounded ? Math.max(config.getTimeoutMs(), 0) : 0;
            String permitId = concurrencySemaphore.tryAcquire(waitMs,
                    config.getConcurrencyLeaseMs(), TimeUnit.MILLISECONDS);
            if (permitId == null) {
                return RateLimitDecision.rateLimited("集群并发已满" + (bounded ? "且排队超时" : ""), 100L);
            }
            // 租期兜底归还；正常路径 afterCompletion 主动 release，幂等由 permitId 保证
            return RateLimitDecision.allow(() -> releaseQuietly(permitId));
        }
        return RateLimitDecision.allow(RateLimitDecision.noop());
    }

    /**
     * Lua 计数原子执行；Redis 异常时返回 null（调用侧按“放行”处理），限流组件故障不打死业务
     */
    private Long evalCounter(String lua, String key, long limit, long ttlMillis) {
        try {
            return redisson.getScript().eval(
                    org.redisson.api.RScript.Mode.READ_WRITE,
                    lua,
                    org.redisson.api.RScript.ReturnType.LONG,
                    List.of(key),
                    String.valueOf(limit),
                    String.valueOf(ttlMillis));
        } catch (Exception e) {
            log.warn("限流 Lua 执行失败，本次降级放行 key={}", key, e);
            return null;
        }
    }

    private void releaseQuietly(String permitId) {
        try {
            concurrencySemaphore.release(permitId);
        } catch (Exception e) {
            // 归还失败靠租期自愈，只告警不打断响应收尾
            log.warn("并发许可归还失败，将由租期兜底回收 permit={} name={}", permitId, semaphoreName, e);
        }
    }
}
