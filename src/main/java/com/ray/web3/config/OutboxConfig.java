package com.ray.web3.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Outbox 配置装配（第 3 周 Day 3-4）：注册 {@link OutboxProperties}
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {
}