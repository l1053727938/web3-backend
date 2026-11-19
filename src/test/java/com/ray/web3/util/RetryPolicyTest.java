package com.ray.web3.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退避策略单测（第 4 周 Day 1-2）
 * <p>
 * 锁死三件事：指数增长与封顶；jitter 落在 {@code [0.5d, 1.5d]}；重试上限判定。
 * 退避算错会导致重试风暴或消息长期不重试，属于“配置正确但代码悄悄放大副作用”的典型。
 */
class RetryPolicyTest {

    @Test
    void 指数退避应按基数翻倍并封顶() {
        RetryPolicy policy = new RetryPolicy(1, 300, 8);

        assertThat(policy.exponentialSeconds(1)).isEqualTo(1);
        assertThat(policy.exponentialSeconds(2)).isEqualTo(2);
        assertThat(policy.exponentialSeconds(3)).isEqualTo(4);
        assertThat(policy.exponentialSeconds(4)).isEqualTo(8);
        assertThat(policy.exponentialSeconds(7)).isEqualTo(64);
        assertThat(policy.exponentialSeconds(20)).isEqualTo(300);
        // 远超上限也不能溢出
        assertThat(policy.exponentialSeconds(Integer.MAX_VALUE)).isEqualTo(300);
    }

    @Test
    void jitter应落在0点5倍到1点5倍之间() {
        RetryPolicy policy = new RetryPolicy(1, 300, 8);

        for (int attempts = 1; attempts <= 8; attempts++) {
            long exponential = policy.exponentialSeconds(attempts);
            long lower = Math.max(1L, Math.round(exponential * 0.5));
            long upper = Math.round(exponential * 1.5);
            for (int i = 0; i < 200; i++) {
                long delay = policy.delaySeconds(attempts);
                assertThat(delay)
                        .as("attempts=%d exponential=%d delay=%d", attempts, exponential, delay)
                        .isBetween(lower, upper);
            }
        }
    }

    @Test
    void 同一重试次数跑100次不应完全相同() {
        RetryPolicy policy = new RetryPolicy(1, 300, 8);
        Set<Long> delays = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            delays.add(policy.delaySeconds(5));
        }
        // 有 jitter 时几乎不可能 100 次撞成同一个值；若相同说明随机源没生效
        assertThat(delays).hasSizeGreaterThan(1);
    }

    @Test
    void 固定随机源时延迟应等于指数值() {
        // 0.5 + 0.5 = 1.0，去掉抖动后便于断言
        RetryPolicy policy = new RetryPolicy(5, 300, 3, () -> 0.5);

        assertThat(policy.delaySeconds(1)).isEqualTo(5);
        assertThat(policy.delaySeconds(2)).isEqualTo(10);
        assertThat(policy.delaySeconds(3)).isEqualTo(20);
    }

    @Test
    void 达到最大次数应判定耗尽() {
        RetryPolicy policy = new RetryPolicy(1, 300, 8);

        assertThat(policy.isExhausted(7)).isFalse();
        assertThat(policy.isExhausted(8)).isTrue();
        assertThat(policy.isExhausted(9)).isTrue();
        assertThat(policy.maxAttempts()).isEqualTo(8);
    }

    @Test
    void 非法参数应被归一化() {
        RetryPolicy policy = new RetryPolicy(0, 0, 0);

        assertThat(policy.baseSeconds()).isEqualTo(1);
        assertThat(policy.capSeconds()).isEqualTo(1);
        assertThat(policy.maxAttempts()).isEqualTo(1);
        assertThat(policy.delaySeconds(1)).isBetween(1L, 2L);
    }

    @Test
    void 默认参数应为1秒基数和5分钟上限() {
        assertThat(RetryPolicy.DEFAULT_BASE_SECONDS).isEqualTo(1);
        assertThat(RetryPolicy.DEFAULT_CAP_SECONDS).isEqualTo(300);
        assertThat(RetryPolicy.DEFAULT_MAX_ATTEMPTS).isEqualTo(8);
        // 生产构造器使用线程本地随机源，至少能正常算出值
        long delay = new RetryPolicy(
                RetryPolicy.DEFAULT_BASE_SECONDS,
                RetryPolicy.DEFAULT_CAP_SECONDS,
                RetryPolicy.DEFAULT_MAX_ATTEMPTS).delaySeconds(1);
        assertThat(delay).isBetween(1L, 2L);
    }
}
