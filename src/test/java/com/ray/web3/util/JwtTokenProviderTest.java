package com.ray.web3.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签发/校验工具离线单测（第 2 周交付 1 的核心逻辑）：
 * 覆盖正常往返、scope 权限判定、路径→所需权限映射、篡改/过期/弱密钥的拒绝
 */
class JwtTokenProviderTest {

    /** 固定 32 字节 Base64 密钥，保证 HS256 强度校验通过且测试可复现 */
    private static final String SECRET =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private JwtTokenProvider provider(long ttlSeconds) {
        return new JwtTokenProvider(SECRET, ttlSeconds, 30L);
    }

    @Test
    void 签发的token能被同密钥校验并还原声明() {
        JwtTokenProvider p = provider(600);
        String token = p.issue("deposit-listener", "chain:read log:query");

        Claims claims = p.parse(token);
        assertThat(claims.getSubject()).isEqualTo("deposit-listener");
        assertThat(JwtTokenProvider.scopeOf(claims)).isEqualTo("chain:read log:query");
        // jti 已写入，为后续吊销机制留扩展点
        assertThat(claims.getId()).isNotBlank();
    }

    @Test
    void scope权限判定按空格分隔精确匹配() {
        assertThat(JwtTokenProvider.hasScope("chain:read log:query", "log:query")).isTrue();
        assertThat(JwtTokenProvider.hasScope("chain:read log:query", "withdraw:request")).isFalse();
        // 前缀不算命中：log:query 不应匹配 log:queryAll
        assertThat(JwtTokenProvider.hasScope("log:queryAll", "log:query")).isFalse();
        assertThat(JwtTokenProvider.hasScope("", "chain:read")).isFalse();
    }

    @Test
    void 路径到所需权限的映射() {
        // 日志/补拉/游标类 → log:query
        assertThat(JwtTokenProvider.requiredScope("/api/chain/logs/transfer/0xabc")).isEqualTo("log:query");
        assertThat(JwtTokenProvider.requiredScope("/api/chain/logs/transfer/backfill/0xabc")).isEqualTo("log:query");
        assertThat(JwtTokenProvider.requiredScope("/api/chain/logs/cursor/0xabc")).isEqualTo("log:query");
        // 充值地址类 → deposit:read
        assertThat(JwtTokenProvider.requiredScope("/api/chain/deposit/wallets")).isEqualTo("deposit:read");
        // 其余链上查询 → chain:read
        assertThat(JwtTokenProvider.requiredScope("/api/chain/status")).isEqualTo("chain:read");
        assertThat(JwtTokenProvider.requiredScope("/api/chain/balance/0x1")).isEqualTo("chain:read");
    }

    @Test
    void 篡改token内容被拒() {
        JwtTokenProvider p = provider(600);
        String token = p.issue("svc", "chain:read");
        // 改动末尾一段（签名位）必然验签失败
        String tampered = token.substring(0, token.length() - 2) + "ab";

        assertThatThrownBy(() -> p.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void 不同密钥无法校验对方token() {
        String otherSecret = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());
        String token = provider(600).issue("svc", "chain:read");

        assertThatThrownBy(() -> new JwtTokenProvider(otherSecret, 600, 30).parse(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void 过期token被拒() {
        // ttl=-2 且允许 30s 偏差：签发即已过期 beyond skew，必判过期
        JwtTokenProvider p = provider(-60);
        String token = p.issue("svc", "chain:read");

        assertThatThrownBy(() -> p.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void 密钥强度不足直接拒绝构造() {
        String weakSecret = Base64.getEncoder().encodeToString("short".getBytes());
        assertThatThrownBy(() -> new JwtTokenProvider(weakSecret, 600, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test
    void 生成的密钥满足HS256强度且可用于往返() {
        String secret = JwtTokenProvider.generateSecret();
        assertThatCode(() -> {
            JwtTokenProvider p = new JwtTokenProvider(secret, 600, 30);
            String token = p.issue("svc", "chain:read");
            p.parse(token);
        }).doesNotThrowAnyException();
    }
}
