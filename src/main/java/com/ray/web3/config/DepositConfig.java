package com.ray.web3.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 充值业务（模块四）配置装配：注册 {@link DepositProperties}
 */
@Configuration
@EnableConfigurationProperties(DepositProperties.class)
public class DepositConfig {
}
