package com.ray.web3.ratelimit;

import com.ray.web3.config.AuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单机守卫四道闸单测：分别隔离全局速率 / 每用户速率 / 每用户日配额 / 并发槽位四道闸门的行为，
 * 每个用例只开一道相关闸门，其余置 0 或关闭，判定结果确定、不依赖真实时间窗口
 */
class LocalRateLimitGuardTest {

    /** 只保留指定闸门，其余关闭：便于隔离验证单道闸逻辑 */
    private static AuthProperties.RateLimit base() {
        AuthProperties.RateLimit cfg = new AuthProperties.RateLimit();
        cfg.setEnabled(true);
        cfg.setMode(AuthProperties.RateLimit.Mode.LOCAL);
        cfg.setQueuePolicy(AuthProperties.RateLimit.QueuePolicy.FAST_FAIL);
        cfg.setPermitsPerSecond(0);   // 关闭全局速率
        cfg.setMaxConcurrent(0);      // 关闭并发闸门
        cfg.setPerUserEnabled(false);
        cfg.setQuotaEnabled(false);
        return cfg;
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("闸门①：全局速率超限立即拒绝，未超则放行")
    void globalRateGate() throws InterruptedException {
        AuthProperties.RateLimit cfg = base();
        cfg.setPermitsPerSecond(1); // 1 令牌/秒
        LocalRateLimitGuard guard = new LocalRateLimitGuard(cfg);

        assertTrue(guard.check("ip:1.1.1.1").isAllowed(), "首个请求应拿到令牌");
        RateLimitDecision second = guard.check("ip:1.1.1.1");
        assertFalse(second.isAllowed(), "紧随其后的第二个请求应因全局速率被拒");
        assertTrue(second.getRejectReason().contains("全局"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("闸门②：每用户速率按 identity 独立分桶，不同用户互不影响")
    void perUserRateGate() throws InterruptedException {
        AuthProperties.RateLimit cfg = base();
        cfg.setPerUserEnabled(true);
        cfg.setUserPermitsPerSecond(1);
        LocalRateLimitGuard guard = new LocalRateLimitGuard(cfg);

        assertTrue(guard.check("sub:alice").isAllowed());
        assertFalse(guard.check("sub:alice").isAllowed(), "同一用户紧随其后应触发每用户速率超限");
        assertTrue(guard.check("sub:bob").isAllowed(), "不同用户有独立桶，不受 alice 影响");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("闸门③：每用户日配额用尽后拒绝，被拒请求不消耗配额")
    void dailyQuotaGate() throws InterruptedException {
        AuthProperties.RateLimit cfg = base();
        cfg.setQuotaEnabled(true);
        cfg.setUserDailyQuota(2);
        LocalRateLimitGuard guard = new LocalRateLimitGuard(cfg);

        assertTrue(guard.check("sub:alice").isAllowed(), "第 1 次在配额内");
        assertTrue(guard.check("sub:alice").isAllowed(), "第 2 次达到配额上限仍放行");
        assertFalse(guard.check("sub:alice").isAllowed(), "第 3 次超配额应被拒");
        assertTrue(guard.check("sub:bob").isAllowed(), "每个用户配额独立，bob 仍有额度");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("闸门④：并发槽位快速失败——占满后拒绝，归还后复用")
    void concurrencyFastFailGate() throws InterruptedException {
        AuthProperties.RateLimit cfg = base();
        cfg.setMaxConcurrent(1); // FAST_FAIL，排队区为 0
        LocalRateLimitGuard guard = new LocalRateLimitGuard(cfg);

        RateLimitDecision first = guard.check("ip:2.2.2.2");
        assertTrue(first.isAllowed(), "首个请求占用唯一并发槽");

        RateLimitDecision second = guard.check("ip:3.3.3.3");
        assertFalse(second.isAllowed(), "并发槽占满且快速失败策略下第二个请求立即被拒");
        assertTrue(second.getRejectReason().contains("并发"));

        first.getRelease().run(); // 归还槽位
        RateLimitDecision reused = guard.check("ip:4.4.4.4");
        assertTrue(reused.isAllowed(), "归还后新请求应能拿到并发槽");
        reused.getRelease().run();
    }
}
