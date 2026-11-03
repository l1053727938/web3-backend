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

    // ===== Day 3-4：广播后状态追踪（BROADCAST → SUCCESS/FAILED 轮询收据）=====

    /** 追踪扫描开关：关闭时只广播不自动推进终态（仍可手动调 queryWithdraw 观测），默认随提币能力开启 */
    private boolean trackEnabled = true;

    /** 追踪轮询间隔(ms)：两次扫描的最小间隔，也是交易从广播到回填终态的最大延迟量级 */
    private long trackIntervalMs = 15000L;

    /** 单轮最多扫描多少笔 BROADCAST 记录：限批防一次性向节点发大量 eth_getTransactionReceipt 打爆配额 */
    private int trackBatchSize = 100;

    /**
     * 成功所需确认数：收据已上块且 (链最新高度 - 收据区块高度 + 1) ≥ 本值才转 SUCCESS，否则留待下轮；
     * 0=收据一旦出现 status=0x1 即判成功（不额外等确认）。与充值侧 confirm-threshold 同口径，防区块重组误判
     */
    private long trackConfirmations = 12L;

    /**
     * 卡单判定阈值(ms)：BROADCAST 停留超过本时长仍未得出终态，即认定为卡单并首次标注 error_message。
     * <p>
     * 年龄从 {@code created_at} 算（{@code updated_at} 已被追踪心跳占用，不再代表状态变更时间）；
     * 取值应按链的出块间隔换算：本值默认 600000（10 分钟）≈ 以太坊 PoS 50 个块，远大于正常打包等待，
     * 宁可偏大误报（只写标注、不改状态），也不要在交易只是慢而未卡时过早标记；3s 出块的链可相应调小
     */
    private long trackStuckTimeoutMs = 600000L;
}
