package com.ray.web3.service.impl;

import com.ray.web3.service.RpcCallService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;

/**
 * 单段 eth_getLogs 调用：网络级异常由 resilience4j 按分段粒度重试；
 * RateLimiter 主动限速平滑请求速率，避免触发 Infura 429 Too Many Requests
 */
@Service
@RequiredArgsConstructor
public class RpcCallServiceImpl implements RpcCallService {

    private final Web3j web3j;

    @Override
    @RateLimiter(name = "rpc-segment")
    @Retry(name = "rpc-segment")
    @CircuitBreaker(name = "rpc-segment")
    public EthLog ethGetLogs(EthFilter filter) {
        try {
            return web3j.ethGetLogs(filter).send();
        } catch (Exception e) {
            throw new IllegalStateException("eth_getLogs RPC 请求失败: " + e.getMessage(), e);
        }
    }
}
