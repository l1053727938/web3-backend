package com.ray.web3.interceptor;

import com.google.common.util.concurrent.RateLimiter;
import com.ray.web3.config.AuthProperties;
import com.ray.web3.util.HttpResponseUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * 查询接口入口令牌桶限流（第 2 周交付 3 的生产应用）：拦截 /api/chain/logs/** 等 eth_getLogs 类高消耗接口
 * <p>
 * 选令牌桶而非漏桶的原因：运营后台批量拉历史日志是合法突发，令牌桶允许把闲置时段积累的
 * 令牌瞬间消耗；速率上限按 Infura 日配额反推，防止单一调用方打爆全局配额。
 * Guava RateLimiter 本身即令牌桶：tryAcquire(timeout) 取不到令牌立即（或等待超时后）拒绝 429
 */
@Slf4j
@RequiredArgsConstructor
public class TokenBucketRateLimitInterceptor implements HandlerInterceptor {

    /** 入口桶：按配置速率即时填充，不设预热爬升（查询接口要的是即时可判的通过/拒绝信号） */
    private final RateLimiter rateLimiter;
    private final AuthProperties.RateLimit config;

    public static TokenBucketRateLimitInterceptor of(AuthProperties.RateLimit config) {
        RateLimiter limiter = RateLimiter.create(config.getPermitsPerSecond());
        return new TokenBucketRateLimitInterceptor(limiter, config);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        boolean acquired = rateLimiter.tryAcquire(1, config.getTimeoutMs(), TimeUnit.MILLISECONDS);
        if (!acquired) {
            log.warn("入口限流拒绝 path={} permitsPerSecond={}",
                    request.getRequestURI(), rateLimiter.getRate());
            HttpResponseUtil.writeJson(response, 429, 429, "请求过于频繁，已触发入口令牌桶限流");
            return false;
        }
        return true;
    }
}
