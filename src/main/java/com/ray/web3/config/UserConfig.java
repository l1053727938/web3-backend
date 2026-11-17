package com.ray.web3.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 用户业务配置装配（第 3 周）：注册 {@link UserProperties}
 */
@Configuration
@EnableConfigurationProperties(UserProperties.class)
public class UserConfig {
}
