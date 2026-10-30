package com.ray.web3.util;

import com.ray.web3.consts.PatternConsts;

import java.math.BigInteger;

/**
 * 链上查询入参校验工具：收拢各 Service 里重复的「null 判断 + 正则 + 中文提示」样板
 * <p>
 * 校验失败一律抛 IllegalArgumentException，由 GlobalExceptionHandler 统一转 400；
 * 口径集中后，新增接口只要调这里就不会出现"同一个参数在两个接口报不同错"的情况
 */
public final class ChainParams {

    private ChainParams() {
    }

    /**
     * 合约地址：0x + 40 位 hex（严格小写前缀，因为该值会原样拼进 eth_getLogs 过滤器发给节点）
     */
    public static void requireContract(String contractAddress) {
        if (!PatternConsts.isAddress(contractAddress)) {
            throw new IllegalArgumentException("合约地址格式非法: " + contractAddress);
        }
    }

    /**
     * 交易哈希：0x + 64 位 hex
     */
    public static void requireTxHash(String txHash) {
        if (!PatternConsts.isTxHash(txHash)) {
            throw new IllegalArgumentException("交易哈希格式非法: " + txHash);
        }
    }

    /**
     * 区块区间：两端非空、起始不为负、from &lt;= to
     */
    public static void requireRange(BigInteger fromBlock, BigInteger toBlock) {
        if (fromBlock == null || toBlock == null || fromBlock.signum() < 0 || fromBlock.compareTo(toBlock) > 0) {
            throw new IllegalArgumentException("区块区间非法: [" + fromBlock + ", " + toBlock + "]");
        }
    }
}
