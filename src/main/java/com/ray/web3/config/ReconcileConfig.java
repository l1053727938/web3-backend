package com.ray.web3.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 对账配置装配（第 5 周 Day 3-4）
 */
@Configuration
@EnableConfigurationProperties(ReconcileProperties.class)
public class ReconcileConfig {
}
