package com.ray.web3.interceptor;

import com.ray.web3.util.ClientIdentityResolver;
import com.ray.web3.util.HttpResponseUtil;
import com.ray.web3.util.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 鉴权拦截器（第 2 周交付 1）：拦截 /api/chain/**，校验 Bearer Token 的签名、时效与 scope
 * <p>
 * 校验流程：提取 Authorization: Bearer &lt;token&gt; → HS256 验签（含时钟偏差容忍）→
 * 过期判定（401）→ scope 与路径所需权限匹配（403）。
 * /actuator/health、/swagger-ui/** 不在拦截注册路径内，即健康检查始终放行
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response, "缺少 Bearer Token");
            return false;
        }
        Claims claims;
        try {
            claims = tokenProvider.parse(header.substring(BEARER_PREFIX.length()));
        } catch (ExpiredJwtException e) {
            writeUnauthorized(response, "Token 已过期");
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            // 签名不匹配/格式非法/被篡改统一归为无效凭证；只回笼统信息，避免泄露失败原因细节
            log.debug("JWT 校验失败 path={} err={}", request.getRequestURI(), e.getMessage());
            writeUnauthorized(response, "Token 无效");
            return false;
        }
        String required = JwtTokenProvider.requiredScope(request.getMethod(), request.getRequestURI());
        if (!JwtTokenProvider.hasScope(JwtTokenProvider.scopeOf(claims), required)) {
            log.warn("JWT scope 不足 path={} sub={} required={}",
                    request.getRequestURI(), claims.getSubject(), required);
            HttpResponseUtil.writeJson(response, 403, 403, "权限不足，需要 scope: " + required);
            return false;
        }
        // 验签通过后把 claims 存入请求属性，供下游限流拦截器按 sub 分 key（ClientIdentityResolver 读取）
        request.setAttribute(ClientIdentityResolver.ATTR_JWT_CLAIMS, claims);
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) {
        HttpResponseUtil.writeJson(response, 401, 401, message);
    }
}
