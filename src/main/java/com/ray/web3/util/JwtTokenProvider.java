package com.ray.web3.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与校验工具（第 2 周：HS256/HMAC 对称签名，面向内部服务间鉴权）
 * <p>
 * 设计约定（见 docs/补充全部路线.md 第 2 周方案）：
 * <ul>
 *   <li>payload 只放非敏感声明：sub（服务/用户标识）、scope（空格分隔权限）、iat/exp、jti</li>
 *   <li>内部服务间有效期 5-15 分钟短时效；jti 为后续吊销机制留扩展点</li>
 *   <li>HMAC 对称密钥全链路共享，未来需第三方验签且不暴露 secret 时再切 RSA（概念预留）</li>
 * </ul>
 * 无状态纯函数类，不依赖 Spring 容器，便于单测与调用方（拦截器/签发脚本）复用
 */
public final class JwtTokenProvider {

    /** 权限范围声明：值为空格分隔的权限清单，如 "chain:read log:query" */
    public static final String CLAIM_SCOPE = "scope";

    private final SecretKey key;
    private final Duration ttl;
    private final Duration clockSkew;

    /**
     * @param secretBase64 Base64 编码的 HMAC 密钥，解码后原始字节须 ≥ 32 字节（HS256 要求 256 bit）
     * @param ttlSeconds   JWT 有效期（秒）
     * @param clockSkewSeconds 校验允许的时钟偏差（秒）
     */
    public JwtTokenProvider(String secretBase64, long ttlSeconds, long clockSkewSeconds) {
        byte[] keyBytes = Base64.getDecoder().decode(secretBase64);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("HS256 密钥原始字节须 ≥ 32 字节（256 bit），当前 " + keyBytes.length);
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.clockSkew = Duration.ofSeconds(clockSkewSeconds);
    }

    /** 生成一个符合 HS256 强度的随机密钥（Base64 编码），供本地联调/配置模板使用 */
    public static String generateSecret() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 签发 JWT
     *
     * @param sub   服务标识或用户 ID
     * @param scope 空格分隔的权限清单，如 "deposit:read log:query"
     * @return 紧凑序列化形式的 token
     */
    public String issue(String sub, String scope) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(sub)
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_SCOPE, scope)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 当前签发 token 的有效期（秒），供签发接口回写过期信息 */
    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    /**
     * 校验签名与时效并返回 claims；任何校验失败统一抛 {@link JwtException}，由调用方转 401
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .clockSkewSeconds(clockSkew.toSeconds())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 从 claims 中读取 scope 权限清单（缺失时视为无权限） */
    public static String scopeOf(Claims claims) {
        String scope = claims.get(CLAIM_SCOPE, String.class);
        return scope == null ? "" : scope;
    }

    /** scope 是否包含目标权限（空格分隔精确匹配，如 "chain:read log:query" 含 log:query） */
    public static boolean hasScope(String scope, String required) {
        for (String s : scope.split(" ")) {
            if (s.equals(required)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按请求路径推导所需权限（提币接口已接入，口径见双参重载）：默认按 GET 语义判定
     */
    public static String requiredScope(String path) {
        return requiredScope("GET", path);
    }

    /**
     * 按请求路径 + HTTP 方法推导所需权限：
     * 提币提交（POST /api/chain/withdraw/**）→ withdraw:submit（花钱操作独立 scope，只读 token 无法发提币）；
     * 提币查询 → withdraw:read；日志/补拉/游标类 → log:query；充值地址类 → deposit:read；其余链上查询 → chain:read
     */
    public static String requiredScope(String httpMethod, String path) {
        if (path.startsWith("/api/chain/withdraw")) {
            return "POST".equalsIgnoreCase(httpMethod) ? "withdraw:submit" : "withdraw:read";
        }
        if (path.startsWith("/api/chain/logs") || path.contains("/backfill") || path.contains("/cursor")) {
            return "log:query";
        }
        if (path.startsWith("/api/chain/deposit")) {
            return "deposit:read";
        }
        return "chain:read";
    }
}
