package com.ray.web3.util;

/**
 * Outbox 退避计算（第 3 周 Day 3-4）
 * <p>
 * 纯函数，便于单测锁定边界：第 1 次重试等待 {@code base}，之后每次翻倍，封顶 {@code max}。
 * 例：base=5s、max=300s → 5, 10, 20, 40, 80, 160, 300, 300...
 * <p>
 * 不做随机抖动：本项目投递器规模小，统一节奏更易观察；多实例大规模重试时可再加 jitter，
 * 但必须在<b>同一函数</b>里改，避免各调用方算出不同退避。
 */
public final class OutboxBackoff {

    private OutboxBackoff() {
    }

    /**
     * @param retryCount   本次失败后的累计重试次数（从 1 开始）
     * @param baseSeconds  退避基数（秒），小于 1 按 1 处理
     * @param maxSeconds   退避上限（秒），小于基数时按基数处理
     * @return 下次投递前应等待的秒数
     */
    public static long delaySeconds(int retryCount, long baseSeconds, long maxSeconds) {
        long base = Math.max(1L, baseSeconds);
        long max = Math.max(base, maxSeconds);
        if (retryCount <= 1) {
            return base;
        }
        long delay = base;
        for (int i = 1; i < retryCount && delay < max; i++) {
            // 先判断再乘 2，避免接近 Long.MAX_VALUE 时溢出
            delay = delay >= max / 2 ? max : delay * 2;
        }
        return Math.min(delay, max);
    }
}