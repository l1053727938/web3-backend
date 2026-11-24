package com.ray.web3.model.vo;

import lombok.Data;

/**
 * 链上孤儿事件（第 5 周 Day 3-4 对账用）
 * <p>
 * 链上命中了平台收款地址，但没有对应的 {@code deposit_record}：
 * 可能是路由遗漏（MISSING），也可能是代币不在白名单（UNKNOWN_TOKEN）。
 */
@Data
public class ChainOrphanRow {

    private Long chainId;

    private String contractAddress;

    private String transactionHash;

    private Long logIndex;

    private String toAddress;

    private String rawValue;

    private String displayValue;

    private Integer decimals;
}
