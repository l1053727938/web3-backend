package com.ray.web3.util;

import org.web3j.protocol.Web3j;

import java.math.BigInteger;

/**
 * 无状态链上查询工具：把多处重复的样板调用与异常包装收拢到一处
 * <p>
 * 刻意只做"一次调用 + 异常转译"，不加 @Retry/@CircuitBreaker：
 * 弹性策略属于 Bean 方法层面的职责（静态工具方法上的注解不会被 AOP 拦截，加了也不生效），
 * 由调用方按自身场景决定——{@code ChainServiceImpl.latestBlockNumber()} 带 @Retry("rpc")，
 * 复用本方法后重试照常生效；补拉与定时扫描则失败即结束（重跑一轮比多等几次退避便宜）
 */
public final class ChainQueryUtils {

    private ChainQueryUtils() {
    }

    /**
     * eth_blockNumber：取最新区块高度，失败统一包装为 IllegalStateException（GlobalExceptionHandler → 502）
     */
    public static BigInteger latestBlockNumber(Web3j web3j) {
        try {
            return web3j.ethBlockNumber().send().getBlockNumber();
        } catch (Exception e) {
            throw new IllegalStateException("查询最新区块高度失败: " + e.getMessage(), e);
        }
    }
}
