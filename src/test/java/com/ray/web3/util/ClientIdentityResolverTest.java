package com.ray.web3.util;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 调用方身份解析单测：验证限流分 key 的优先级——JWT sub → X-Forwarded-For → 对端地址 → anonymous
 */
class ClientIdentityResolverTest {

    @Test
    @DisplayName("存在 JWT claims 且 sub 非空时，身份取 sub:xxx")
    void identityFromJwtSubject() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("alice");
        when(request.getAttribute(ClientIdentityResolver.ATTR_JWT_CLAIMS)).thenReturn(claims);

        assertEquals("sub:alice", ClientIdentityResolver.identity(request));
    }

    @Test
    @DisplayName("无 JWT 时回退 X-Forwarded-For 首跳")
    void identityFallsBackToForwardedFor() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ClientIdentityResolver.ATTR_JWT_CLAIMS)).thenReturn(null);
        when(request.getHeader("X-Forwarded-For")).thenReturn(" 203.0.113.7, 70.41.3.18 ");

        assertEquals("ip:203.0.113.7", ClientIdentityResolver.identity(request));
    }

    @Test
    @DisplayName("无 JWT 无代理头时回退容器对端地址")
    void identityFallsBackToRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ClientIdentityResolver.ATTR_JWT_CLAIMS)).thenReturn(null);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.9");

        assertEquals("ip:10.0.0.9", ClientIdentityResolver.identity(request));
    }

    @Test
    @DisplayName("JWT claims 存在但 sub 为空时，仍回退 IP 而非产出 sub: 空串")
    void blankSubjectFallsBackToIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("  ");
        when(request.getAttribute(ClientIdentityResolver.ATTR_JWT_CLAIMS)).thenReturn(claims);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        assertEquals("ip:10.0.0.1", ClientIdentityResolver.identity(request));
    }

    @Test
    @DisplayName("对端地址解析不到时返回 anonymous 兜底")
    void anonymousFallback() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("");

        assertEquals("anonymous", ClientIdentityResolver.clientIp(request));
    }
}
