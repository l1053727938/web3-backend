package com.ray.web3.util;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 重试退避策略（第 4 周 Day 1-2）
 * <p>
 * 公式：{@code delay = min(base * 2^(attempts-1), cap) * (0.5 + random())}，
 * 默认 {@code base=1s, cap=5min, maxAttempts=8}。
 * <p>
 * <b>为什么不能立即 requeue</b>：1000 条消息同时失败，立即重投就是 1000 条在同一秒打回
 * 刚缓过来的数据库/下游，形成重试风暴。指数退避把重试摊开到更长的时间窗。
 * <p>
 * <b>为什么要 jitter</b>：没有抖动时所有实例的退避时刻完全对齐（同一 base、同一失败时刻），
 * 等于制造一个周期性的定时脉冲；乘以 {@code [0.5, 1.5)} 的随机系数后，
 * 重试请求在时间轴上被打散。
 * <p>
 * <b>为什么 cap 在抖动前生效</b>：先封顶再抖动，单次最大等待为 {@code 1.5 * cap}（默认 7.5min），
 * 不会出现“指数爆炸后 jitter 再放大”的不可控延迟。
 * <p>
 * 纯函数（除随机源外），不碰数据库/线程睡眠，便于单测。
 */
public final class RetryPolicy {

    /** 默认退避基数：1 秒 */
    public static final long DEFAULT_BASE_SECONDS = 1L;

    /** 默认退避上限：5 分钟 */
    public static final long DEFAULT_CAP_SECONDS = 300L;

    /** 建议最大重试次数：超过后进入死信队列 */
    public static final int DEFAULT_MAX_ATTEMPTS = 8;

    private final long baseSeconds;
    private final long capSeconds;
    private final int maxAttempts;
    private final DoubleSupplier random;

    public RetryPolicy(long baseSeconds, long capSeconds, int maxAttempts) {
        this(baseSeconds, capSeconds, maxAttempts, ThreadLocalRandom.current()::nextDouble);
    }

    /**
     * @param random 返回 {@code [0, 1)} 的随机源；生产用线程本地随机，测试可注入固定值
     */
    public RetryPolicy(long baseSeconds, long capSeconds, int maxAttempts, DoubleSupplier random) {
        this.baseSeconds = Math.max(1L, baseSeconds);
        this.capSeconds = Math.max(this.baseSeconds, capSeconds);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.random = random;
    }

    /**
     * 本次失败后应等待的秒数（已含 jitter）。
     *
     * @param attempts 本次失败后的累计失败次数，从 1 开始；第 1 次等待 {@code base}
     */
    public long delaySeconds(int attempts) {
        long exponential = exponentialSeconds(attempts);
        double jittered = exponential * (0.5 + random.getAsDouble());
        return Math.max(1L, Math.round(jittered));
    }

    /**
     * 未加抖动的退避值，仅用于可读性与单测断言：
     * 第 1 次 = base，之后翻倍，封顶 cap。
     */
    public long exponentialSeconds(int attempts) {
        if (attempts <= 1) {
            return baseSeconds;
        }
        long delay = baseSeconds;
        for (int i = 1; i < attempts && delay < capSeconds; i++) {
            // 先判断再乘 2，避免接近 Long.MAX_VALUE 时溢出
            delay = delay >= capSeconds / 2 ? capSeconds : delay * 2;
        }
        return Math.min(delay, capSeconds);
    }

    /** 累计失败次数是否已达到上限（达到即写死信，不再自动重试） */
    public boolean isExhausted(int attempts) {
        return attempts >= maxAttempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public long baseSeconds() {
        return baseSeconds;
    }

    public long capSeconds() {
        return capSeconds;
    }
}
