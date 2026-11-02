package com.ray.web3.ratelimit;

/**
 * 限流守卫：把“全局速率 → 每用户速率 → 每用户总量配额 → 并发槽位（有界排队/快速失败）”
 * 四道闸封装成一次判定，屏蔽 LOCAL（进程内 Guava/JDK 原语）与 DISTRIBUTED（Redisson 分布式原语）的差异。
 * <p>
 * 闸门顺序刻意把“并发槽位”放最后：前面三道是纯检查不占资源，最后一道才占用处理槽位并要求归还——
 * 避免请求一边占着槽位一边等速率令牌，白白锁死并发预算
 */
public interface RateLimitGuard {

    /**
     * 依次过闸；任一闸门挡住立即返回 reject，全过返回 allow（携带并发槽位归还凭据）。
     * BOUNDED_QUEUE 策略下本方法可能阻塞等待（最多 timeoutMs），中断语义交还调用方处理
     *
     * @param identity 调用方标识（{@code ClientIdentityResolver.identity} 产出：sub:xxx 或 ip:xxx）
     */
    RateLimitDecision check(String identity) throws InterruptedException;
}
