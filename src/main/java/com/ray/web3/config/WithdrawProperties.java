package com.ray.web3.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigInteger;

/**
 * 提币发交易（第 3 周：写与发交易）配置：对应 web3.withdraw.*
 * <p>
 * 定位为提币网关的执行层参数（第 8 周补业务编排）。默认关闭：私钥未注入的环境里提币能力整体不装配，
 * 与第 2 周 security.* 默认关闭同一套"不配就不存在"的哲学，防止误触发真实资金操作
 */
@Data
@ConfigurationProperties(prefix = "web3.withdraw")
public class WithdrawProperties {

    /** 总开关；关闭时签名/估算/提币 Bean 不创建 */
    private boolean enabled = false;

    /**
     * 热钱包裸私钥（0x + 64 位 hex）：仅限本地/测试网联调，经环境变量注入、禁止入库明文；
     * 生产切 KMS/HSM 时只替换取值来源（第 8 周），签名算法与 TransactionSigner 不变
     */
    private String hotWalletPrivateKey;

    /**
     * gas 价格安全系数：对 eth_gasPrice 的上乘倍率（≥1.0），主网拥堵时提升打包概率；
     * 用整数千分比表达避免浮点比较歧义（1200 = 1.2 倍）
     */
    private long gasPriceBps = 1200L;

    /**
     * gas 用量安全垫：对 eth_estimateGas 结果的加量比例（千分比，1200 = 估算值 ×1.2）。
     * 估算基于当前状态，上链时状态变差（如代币内部记账分支变化）需要余量兜底
     */
    private long gasLimitBps = 1200L;

    /** gasLimit 封顶：估算异常放大时防单笔交易烧穿热钱包 ETH（普通 ERC-20 转账实测约 5 万 gas） */
    private BigInteger maxGasLimit = new BigInteger("500000");
}
