package com.ray.web3.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 充值业务（模块四 + 第 3 周消费者）配置装配：注册 {@link DepositProperties} 与
 * {@link DepositConsumerProperties}
 */
@Configuration
@EnableConfigurationProperties({DepositProperties.class, DepositConsumerProperties.class})
public class DepositConfig {
}
