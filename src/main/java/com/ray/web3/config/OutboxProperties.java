package com.ray.web3.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox 投递配置（第 3 周 Day 3-4）
 * <p>
 * 参数只描述投递器行为，不参与“业务与消息同事务”的正确性：
 * 即使投递器停掉，业务照常落库（消息留在 outbox），恢复后自动续投。
 */
@Data
@ConfigurationProperties(prefix = "web3.outbox")
public class OutboxProperties {

    /** 是否启用定时投递器；关闭后消息只落库不发送，可手工/后续任务消费 */
    private boolean enabled = true;

    /** 投递轮询间隔（毫秒） */
    private long dispatchIntervalMs = 1000L;

    /** 单轮最多扫描条数，对应 SELECT ... LIMIT ? FOR UPDATE SKIP LOCKED */
    private int batchSize = 200;

    /** 最大重试次数，达到后置 FAILED 停止自动投递（W4 的 8 次上限） */
    private int maxRetries = 8;

    /** 退避基数（秒）：第 1 次重试等待 base，之后每轮翻倍 */
    private long retryBaseSeconds = 5L;

    /** 退避上限（秒）：指数增长不超过该值 */
    private long retryMaxSeconds = 300L;
}