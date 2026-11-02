package com.ray.web3.interceptor;

import com.ray.web3.config.AuthProperties;
import com.ray.web3.ratelimit.RateLimitDecision;
import com.ray.web3.ratelimit.RateLimitGuard;
import com.ray.web3.util.ClientIdentityResolver;
import com.ray.web3.util.HttpResponseUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 查询接口入口限流拦截器（洪峰防护总闸）：拦截 /api/chain/logs/**、/api/chain/block/**。
 * <p>
 * 自身只做“流程编排”，全部判定委托 {@link RateLimitGuard}：
 * 全局速率 → 每用户速率 → 每用户日配额 → 并发槽位（有界排队或快速失败，由
 * {@code security.rate-limit.queue-policy} 切换；LOCAL/DISTRIBUTED 由 {@code mode} 切换）。
 * <p>
 * 并发槽位的生命周期必须跨过整个请求处理：preHandle 里占用、{@code afterCompletion} 里归还。
 * 不能放在 postHandle —— 它在异常路径不执行，会泄漏槽位；afterCompletion 对
 * “preHandle 返回 true”的请求必定执行，是唯一正确的归还点
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 放行时写入的请求属性：并发槽位归还凭据，afterCompletion 取出执行 */
    private static final String ATTR_RELEASE = "web3.ratelimit.release";

    private final RateLimitGuard guard;
    private final AuthProperties.RateLimit config;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String identity = ClientIdentityResolver.identity(request);
        RateLimitDecision decision;
        try {
            decision = guard.check(identity);
        } catch (InterruptedException e) {
            // 排队等待中被中断（如容器超时取消）：恢复中断标志并按拒绝处理，绝不吞中断
            Thread.currentThread().interrupt();
            reject(response, "请求等待并发槽位时被中断");
            return false;
        } catch (RuntimeException e) {
            // 守卫自身故障（Redis/缓存异常兜底后仍抛出）不应打死接口：告警并放行
            log.error("限流守卫异常，本次放行 path={} identity={}", request.getRequestURI(), identity, e);
            return true;
        }

        if (!decision.isAllowed()) {
            if (decision.getRetryAfterMs() > 0) {
                // 给友好调用方一个重试提示（秒，向上取整）
                response.setHeader("Retry-After", String.valueOf(Math.max(1, (decision.getRetryAfterMs() + 999) / 1000)));
            }
            log.warn("入口限流拒绝 path={} identity={} mode={} policy={} reason={}",
                    request.getRequestURI(), identity, config.getMode(), config.getQueuePolicy(),
                    decision.getRejectReason());
            HttpResponseUtil.writeJson(response, 429, 429, decision.getRejectReason());
            return false;
        }
        request.setAttribute(ATTR_RELEASE, decision.getRelease());
        return true;
    }

    /**
     * 归还并发槽位：afterCompletion 对 preHandle 返回 true 的请求必定执行（含异常路径）；
     * 释放动作内部已做异常兜底（分布式侧靠租期自愈），这里再挡一层运行时异常保证收尾不受影响
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object release = request.getAttribute(ATTR_RELEASE);
        if (release instanceof Runnable runnable) {
            try {
                runnable.run();
            } catch (RuntimeException e) {
                log.warn("并发槽位归还异常 path={}", request.getRequestURI(), e);
            }
        }
    }

    private void reject(HttpServletResponse response, String reason) {
        HttpResponseUtil.writeJson(response, 429, 429, reason);
    }
}
