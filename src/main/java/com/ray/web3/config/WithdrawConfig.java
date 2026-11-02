package com.ray.web3.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 提币发交易（第 3 周）配置装配：注册 {@link WithdrawProperties}。
 * 装配本身无条件——Bean 是否创建由 web3.withdraw.enabled 的 @ConditionalOnProperty 决定，
 * 关闭时配置类只多一份元数据，零运行开销
 */
@Configuration
@EnableConfigurationProperties(WithdrawProperties.class)
public class WithdrawConfig {
}
