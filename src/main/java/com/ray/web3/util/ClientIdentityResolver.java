package com.ray.web3.util;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 调用方身份解析（限流分 key 用）：统一“用户级闸门”的 key 口径。
 * <p>
 * 优先级：JWT {@code sub}（{@code JwtAuthInterceptor} 验签通过后写入 request 属性）→
 * 反向代理透传的 {@code X-Forwarded-For} 首段 → 容器对端地址。
 * 未开启 JWT 时自然回退 IP，保证限流维度不丢
 */
public final class ClientIdentityResolver {

    /** JwtAuthInterceptor 验签成功后写入的请求属性名 */
    public static final String ATTR_JWT_CLAIMS = "web3.jwt.claims";

    private ClientIdentityResolver() {
    }

    /** 限流/配额分 key 的调用方标识（每用户一桶、每用户一份配额），解析不到返回 anonymous 兜底而非 null */
    public static String identity(HttpServletRequest request) {
        Object claims = request.getAttribute(ATTR_JWT_CLAIMS);
        if (claims instanceof Claims c && c.getSubject() != null && !c.getSubject().isBlank()) {
            return "sub:" + c.getSubject();
        }
        return "ip:" + clientIp(request);
    }

    /** 客户端 IP：X-Forwarded-For 取第一跳（最接近客户端），多级代理/伪造头只影响 key 归属不影响安全闸门本身 */
    public static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "anonymous" : remote;
    }
}
