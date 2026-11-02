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

        /** 总开关；关闭时限流拦截器不注册 */
        private boolean enabled = false;

        /**
         * 限流模式：LOCAL=进程内存桶（Guava，单实例口径，多副本各限各的）；
         * DISTRIBUTED=Redisson 分布式桶（RateType.OVERALL，所有实例合计同一口径，多实例部署用这个）
         */
        private Mode mode = Mode.LOCAL;

        /**
         * 洪峰策略：FAST_FAIL=抢不到令牌/许可立即 429（默认，配合 timeout-ms=0）；
         * BOUNDED_QUEUE=最多排队 timeout-ms 毫秒，超预算才 429（有界等待，平滑吸收短时突发）。
         * 配置键 `queue-policy` 由 Spring 宽松绑定自动映射到驼峰字段 queuePolicy
         */
        private QueuePolicy queuePolicy = QueuePolicy.FAST_FAIL;

        /**
         * 入口桶填充速率（许可/秒，DISTRIBUTED 时为全集群合计口径）：按 Infura 每日配额反推——
         * 例：单次日志查询接口平均触发 N 次 RPC，日均配额可支撑 QPS = 日配额/(86400*N)，取安全下限。
         * 设为 0 或负=关闭全局速率闸门（只用并发与配额两道闸）
         */
        private double permitsPerSecond = 5.0D;

        /**
         * 等待令牌超时（毫秒）：0=取不到立即拒绝（快速失败，返回 429）；
         * &gt;0=最多阻塞等待该时长，体现令牌桶“排队容忍短时突发”的另一面；BOUNDED_QUEUE 模式下必填且应 >0
         */
        private long timeoutMs = 0L;

        /**
         * 最大并发许可数（同时在处理的请求数上限）：与下游容量对齐（Hikari 连接数 / Tomcat 线程数），
         * 防“速率没超但慢请求堆积打垮 DB/线程”。设为 0 或负=关闭并发闸门。
         * DISTRIBUTED 下是全集群合计口径（Redisson RSemaphore），LOCAL 下是本实例口径（JDK Semaphore）
         */
        private int maxConcurrent = 20;

        /**
         * 排队区容量（BOUNDED_QUEUE 模式生效）：最多允许多少个请求在“等处理槽位”；
         * 队满则新请求立即 429，避免洪峰下无限排队耗完 Tomcat 线程与内存
         */
        private int queueCapacity = 50;

        /**
         * 并发许可租期（毫秒，仅 DISTRIBUTED 生效）：实例崩溃未能归还许可时，到期自动释放，防许可泄漏
         */
        private long concurrencyLeaseMs = 60_000L;

        /** 每用户速率限流开关：防单个大户独占全局额度；key 优先 JWT sub，无 JWT 回退客户端 IP */
        private boolean perUserEnabled = false;

        /** 每用户速率（许可/秒）：perUserEnabled=true 时生效 */
        private double userPermitsPerSecond = 2.0D;

        /** 每用户总量配额开关：累计调用量超上限即拒（与速率无关），按天分键自动重置；防被刷积分 */
        private boolean quotaEnabled = false;

        /** 每用户每日总量上限（次/天）：quotaEnabled=true 时生效；设 0 或负=不限 */
        private long userDailyQuota = 1000L;

        /** 限流模式 */
        public enum Mode {
            /** 进程内存桶（Guava），单实例口径 */
            LOCAL,
            /** Redisson 分布式桶，全集群 OVERALL 口径，多实例部署用这个 */
            DISTRIBUTED
        }

        /** 洪峰策略 */
        public enum QueuePolicy {
            /** 快速失败：抢不到立即 429，不占用线程等待 */
            FAST_FAIL,
            /** 有界排队：最多等 timeout-ms，排不上才 429（牺牲少量延迟平滑吸收突发） */
            BOUNDED_QUEUE
        }
    }
}
