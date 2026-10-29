package com.ray.web3.model.vo;

import java.math.BigInteger;

/**
 * ERC-20 Transfer 日志视图对象（由 eth_getLogs 结果解析而来）
 */
public record TransferLogVO(
        BigInteger blockNumber,
        String transactionHash,
        BigInteger logIndex,
        String contractAddress,
        String from,
        String to,
        BigInteger value) {
}
