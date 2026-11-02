package com.ray.web3.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API 鉴权与入口限流（第 2 周：对外 API 安全打底）配置：对应 security.*
 * <p>
 * 定位为后续资产网关的“对外 API 安全”前置能力：链上读接口（尤其 eth_getLogs 类高配额消耗查询）
 * 一旦被封装成接口暴露，就需要回答“谁可以调用（JWT 鉴权）”与“调用太快怎么办（令牌桶限流）”。
 * 默认全部关闭，不影响现有本地开发链路；联调验证时按配置说明打开。
 */
@Data
@ConfigurationProperties(prefix = "security")
public class AuthProperties {

    /** JWT 鉴权配置：保护 /api/chain/** 查询接口，actuator/swagger 不在拦截范围（即健康检查放行） */
    private Jwt jwt = new Jwt();

    /** 查询接口入口令牌桶限流配置：保护 /api/chain/logs/** 等 eth_getLogs 类高消耗接口 */
    private RateLimit rateLimit = new RateLimit();

    @Data
    public static class Jwt {

        /** 总开关；关闭时 JwtAuthInterceptor 不注册，所有接口保持原样放行 */
        private boolean enabled = false;

        /**
         * HMAC-SHA256 对称密钥（Base64 编码，原始字节须 ≥ 32 字节即 256 bit，JJWT 强校验）。
         * 生产经环境变量注入，禁止入库明文
         */
        private String secret;

        /** JWT 有效期（秒）：内部服务间建议 5-15 分钟短时效，而非数天 */
        private long ttlSeconds = 600L;

        /** 校验时允许的时钟偏差（秒），避免多机部署轻微 clock skew 误杀 */
        private long clockSkewSeconds = 30L;

        /**
         * 签发接口 {@code POST /api/auth/token} 的访问口令：请求头 {@code X-Issuer-Key} 必须与此一致才发 token。
         * 留空表示关闭签发接口（返回 503）——安全默认，防 token 被任意调用方铸造；本地自测时显式设一个值即可
         */
        private String issuerAccessKey;
    }

    @Data
    public static class RateLimit {

        /** 总开关；关闭时 TokenBucketRateLimitInterceptor 不注册 */
        private boolean enabled = false;

        /**
         * 令牌填充速率（许可/秒）：按 Infura 每日配额反推——
         * 例：单次日志查询接口平均触发 N 次 RPC，日均配额可支撑 QPS = 日配额/(86400*N)，取安全下限
         */
        private double permitsPerSecond = 5.0D;

        /**
         * 等待令牌超时（毫秒）：0=取不到立即拒绝（快速失败，返回 429）；
         * &gt;0=最多阻塞等待该时长，体现令牌桶“排队容忍短时突发”的另一面
         */
        private long timeoutMs = 0L;
    }
}
