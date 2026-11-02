package com.ray.web3.util;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 有界排队原语（洪峰策略 2：BOUNDED_QUEUE）
 * <p>
 * 语义：最多 {@code maxConcurrent} 个请求同时在处理，另有最多 {@code queueCapacity} 个请求
 * <b>排队等待</b>；每个等待者最多阻塞 {@code timeoutMs} 毫秒。三者之外的请求<b>立即拒绝</b>——
 * 排队是“有界的”，不会因洪峰无限堆积拖垮调用方线程与内存。
 * <p>
 * 为什么不用 {@code Semaphore(maxConcurrent + queueCapacity)} 一把梭：那样无法区分“正在处理”
 * 与“正在排队”，也无法对“排队人数”单独设上限；本类用 公平信号量（保证 FIFO 排队顺序）
 * + 等待计数器 双约束，语义精确。
 * <p>
 * 与令牌桶（限速：每秒最多几个）互补：令牌桶管“到达速率”，本类管“在飞数量 + 排队预算”。
 * 1 万用户 1 秒洪峰下，两者共同决定：放行 → 排队 → 超预算 429 的分层行为
 */
public final class BoundedQueueRateLimiter {

    /** 处理槽位：公平模式，等待者按 FIFO 拿到槽位（排队顺序即到达顺序） */
    private final Semaphore slots;

    /** 正在排队的线程数：上限 queueCapacity，超了直接拒（不入队） */
    private final AtomicInteger waiting = new AtomicInteger(0);

    private final int maxConcurrent;
    private final int queueCapacity;
    private final long timeoutMs;

    public BoundedQueueRateLimiter(int maxConcurrent, int queueCapacity, long timeoutMs) {
        if (maxConcurrent <= 0 || queueCapacity < 0 || timeoutMs < 0) {
            throw new IllegalArgumentException("maxConcurrent 须>0，queueCapacity/timeoutMs 须>=0");
        }
        this.slots = new Semaphore(maxConcurrent, true);
        this.maxConcurrent = maxConcurrent;
        this.queueCapacity = queueCapacity;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 申请一个处理槽位：
     * ① 有空槽 → 立即占用，返回槽位句柄（用完必须 release，放 finally 中）；
     * ② 无空槽但排队人数未到上限 → 进入 FIFO 队列等，最多 timeoutMs；
     * ③ 排队人数已满或等待超时 → 返回 null（调用方 429）
     */
    public Runnable tryAcquire() throws InterruptedException {
        // 快路径：有现成槽位直接占（availablePermits 只读判断，真正占用仍靠 acquire 的原子性）
        if (slots.availablePermits() > 0) {
            slots.acquire();
            return slots::release;
        }
        // 排队预算：waiting 已满 = 队也排不进去，立即拒（不占线程干等）
        if (waiting.incrementAndGet() > queueCapacity) {
            waiting.decrementAndGet();
            return null;
        }
        try {
            boolean ok = slots.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            return ok ? slots::release : null;
        } finally {
            waiting.decrementAndGet();
        }
    }

    /** 当前排队中的请求数（观测/测试用） */
    public int waitingCount() {
        return waiting.get();
    }

    /** 当前空闲处理槽数（观测/测试用） */
    public int availableSlots() {
        return slots.availablePermits();
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }
}
