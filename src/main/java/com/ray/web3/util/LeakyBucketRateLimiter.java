package com.ray.web3.util;

import java.util.concurrent.locks.LockSupport;

/**
 * 漏桶限流器（第 2 周交付 3 对比实验用，输出速率恒定的排队式限流）
 * <p>
 * 实现为“事件间隔”模型：为每个请求按计划时刻 lastLeakNs + intervalNs 排队，
 * 线程阻塞到属于自己的时刻才被放行 —— 无论入流多急（入队多快），出流严格以 1/interval 匀速。
 * 与令牌桶的本质差异：桶容量不随闲置积累，<b>不允许任何突发</b>，
 * 适合垫在监听器与节点 RPC 之间换取下游平滑性（牺牲少量延迟）
 */
public final class LeakyBucketRateLimiter {

    /** 漏速：每 intervalNanos 漏出一个请求，输出速率恒为 1e9/intervalNanos 每秒 */
    private final long intervalNanos;

    private long lastLeakNanos;

    public LeakyBucketRateLimiter(double permitsPerSecond) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond 须为正数");
        }
        this.intervalNanos = (long) (1_000_000_000L / permitsPerSecond);
    }

    /**
     * 取得一个许可：阻塞调用线程直到属于自己的匀速时刻到达。
     * 同桶多线程各自分到依次排开的时刻，宏观即恒定速率出水
     */
    public synchronized void acquire() {
        long now = System.nanoTime();
        long due = Math.max(lastLeakNanos + intervalNanos, now);
        lastLeakNanos = due;
        long waitNanos = due - now;
        if (waitNanos > 0) {
            LockSupport.parkNanos(waitNanos);
        }
    }
}
