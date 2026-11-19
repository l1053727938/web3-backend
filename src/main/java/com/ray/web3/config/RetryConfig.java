package com.ray.web3.config;

import com.ray.web3.util.RetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 重试策略装配（第 4 周 Day 1-2）
 * <p>
 * 退避参数全部来自 {@link OutboxProperties}，不在投递器里硬编码；
 * 生产可按下游恢复速度调整 base/cap，不需要改代码。
 */
@Configuration
public class RetryConfig {

    @Bean
    public RetryPolicy retryPolicy(OutboxProperties properties) {
        return new RetryPolicy(
                properties.getRetryBaseSeconds(),
                properties.getRetryMaxSeconds(),
                properties.getMaxRetries());
    }
}
