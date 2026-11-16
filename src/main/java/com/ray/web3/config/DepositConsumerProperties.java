package com.ray.web3.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 充值消息消费入账配置（第 3 周 Day 5-6）
 * <p>
 * 消费者拿到的是充值事件（depositNo/userId/currency/amount），要把钱记到哪个账户
 * 由这里决定：用户账户按 {@code (userId, account_type, currency)} 查 {@code account} 表，
 * 平台代收负债账户用固定 ID 配置。
 */
@Data
@ConfigurationProperties(prefix = "web3.deposit.consumer")
public class DepositConsumerProperties {

    /**
     * 平台代收负债账户 ID（account_type=PLATFORM）。
     * 本地测试数据默认 900101；生产必须通过环境变量覆盖。
     */
    private long platformLiabilityAccountId = 0L;

    /** 用户账户类型：充值默认入 SPOT */
    private String userAccountType = "SPOT";
}