package com.ray.web3.ratelimit;

/**
 * 限流判定结果（守卫链的统一返回）：
 * <ul>
 *   <li>{@code allowed=true}：全部闸门放行，{@code release} 为并发槽位归还凭据（未占并发槽时为空操作），
 *       拦截器必须在 {@code afterCompletion} 归还，否则槽位泄漏</li>
 *   <li>{@code allowed=false}：任一闸门挡住，{@code rejectReason} 直接回给调用方，{@code retryAfterMs}
 *       用于 429 的 {@code Retry-After} 响应头（0 表示不给）</li>
 * </ul>
 */
public final class RateLimitDecision {

    private final boolean allowed;
    private final String rejectReason;
    private final long retryAfterMs;
    private final Runnable release;

    private RateLimitDecision(boolean allowed, String rejectReason, long retryAfterMs, Runnable release) {
        this.allowed = allowed;
        this.rejectReason = rejectReason;
        this.retryAfterMs = retryAfterMs;
        this.release = release;
    }

    /** 放行：release 为并发许可归还动作，无需归还时传 {@link #noop()} */
    public static RateLimitDecision allow(Runnable release) {
        return new RateLimitDecision(true, null, 0L, release == null ? noop() : release);
    }

    /** 拒绝：速率超限（429），retryAfterMs 告知调用方多久后可重试 */
    public static RateLimitDecision rateLimited(String reason, long retryAfterMs) {
        return new RateLimitDecision(false, reason, retryAfterMs, noop());
    }

    /** 拒绝：总量配额用尽（403 语义更准，此处统一 429，由 reason 区分） */
    public static RateLimitDecision quotaExceeded(String reason) {
        return new RateLimitDecision(false, reason, 0L, noop());
    }

    /** 空归还动作 */
    public static Runnable noop() {
        return () -> {
        };
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }

    public Runnable getRelease() {
        return release;
    }
}
