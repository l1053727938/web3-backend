package com.ray.web3.util;

import com.google.common.util.concurrent.RateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流对比实验（第 2 周交付 3）：用 Guava RateLimiter（令牌桶）与自研
 * {@link LeakyBucketRateLimiter}（漏桶）对同一突发流量施加限流，验证两者核心行为差异——
 * <b>令牌桶允许突发（闲置时段积累令牌可瞬间消耗），漏桶输出恒定速率（不积累、拒绝突发）</b>。
 * <p>
 * 这正是方案里“读接口用令牌桶、节点 RPC 用漏桶”的实证依据：
 * 运营后台批量拉历史日志的合法突发由令牌桶放行；垫在监听器与 Infura 之间的漏桶把瞬时高并发
 * 抹平成稳定速率，避免触发服务商 429/封禁。
 * 时间断言均留足冗余 + {@code @Timeout} 兜底，避免 CI 抖动误报
 */
class RateLimiterComparisonTest {

    /** 目标速率：10 许可/秒 → 令牌间隔约 100ms */
    private static final double PERMITS_PER_SECOND = 10.0D;

    /** 突发请求数 */
    private static final int BURST = 10;

    /**
     * 令牌桶：空闲 1.1s 后（SmoothBursty 最多存 1 秒的令牌，即 ~10 枚）发起突发，
     * 全部 tryAcquire 应立即成功——体现“允许短时突发”
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void 令牌桶空闲积累令牌后放行突发() throws InterruptedException {
        RateLimiter bucket = RateLimiter.create(PERMITS_PER_SECOND);
        bucket.acquire();                 // 启动计时基准（消耗首枚即时令牌）
        TimeUnit.MILLISECONDS.sleep(1100); // 闲置：按 10/s 回填，最多存约 10 枚

        int immediate = 0;
        long start = System.nanoTime();
        for (int i = 0; i < BURST; i++) {
            // 非阻塞取令牌：桶里有就立即成功
            if (bucket.tryAcquire(0, TimeUnit.MILLISECONDS)) {
                immediate++;
            }
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // 积累的令牌足以瞬间消化整波突发（留 1 枚容差）
        assertThat(immediate).isGreaterThanOrEqualTo(BURST - 1);
        // 且几乎不等待，证明是“突发放行”而非“排队匀速”
        assertThat(elapsedMs).isLessThan(300L);
    }

    /**
     * 漏桶：同样空闲 1.1s 后发起突发，出流仍严格以 1/速率匀速漏出，
     * 耗时 ≈ BURST / 速率——体现“不积累、拒绝突发”
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void 漏桶空闲后仍匀速输出不放大突发() {
        LeakyBucketRateLimiter leaky = new LeakyBucketRateLimiter(PERMITS_PER_SECOND);
        leaky.acquire();                  // 建立漏出基准时刻
        try {
            TimeUnit.MILLISECONDS.sleep(1100); // 闲置：漏桶不因此积累任何“额度”
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long start = System.nanoTime();
        for (int i = 0; i < BURST; i++) {
            leaky.acquire();              // 阻塞到属于自己的匀速时刻
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // 理论耗时 ≈ (BURST-1)*100ms = 900ms；断言远大于令牌桶的放行耗时，证明无突发放大
        assertThat(elapsedMs).isGreaterThanOrEqualTo(700L);
    }

    /**
     * 令牌桶令牌耗尽后拒绝快速失败：突发规模超过存量时，tryAcquire 立即返回 false（对应入口 429）
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void 令牌桶超出存量立即拒绝() {
        RateLimiter bucket = RateLimiter.create(PERMITS_PER_SECOND);
        bucket.acquire(); // 消耗首枚，存量清零

        int rejected = 0;
        for (int i = 0; i < 30; i++) {
            if (!bucket.tryAcquire(0, TimeUnit.MILLISECONDS)) {
                rejected++;
            }
        }
        // 30 次突发远超 1 秒存量(~10)，必有相当比例被立即拒绝，而非无限排队
        assertThat(rejected).isGreaterThan(0);
    }

    /**
     * 漏桶参数非法：非正速率应在构造期拒绝
     */
    @Test
    void 漏桶速率非正数拒绝构造() {
        assertThat(new LeakyBucketRateLimiter(PERMITS_PER_SECOND) != null).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LeakyBucketRateLimiter(0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LeakyBucketRateLimiter(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
