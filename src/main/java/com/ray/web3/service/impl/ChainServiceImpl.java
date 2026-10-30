package com.ray.web3.service.impl;

import com.ray.web3.config.InfuraProperties;
import com.ray.web3.model.vo.BalanceVO;
import com.ray.web3.model.vo.ChainStatusVO;
import com.ray.web3.service.ChainService;
import com.ray.web3.util.ChainQueryUtils;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.utils.Convert;

import java.math.BigInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChainServiceImpl implements ChainService {

    private final Web3j web3j;
    private final InfuraProperties infuraProperties;

    @Override
    @Retry(name = "rpc")
    @CircuitBreaker(name = "rpc")
    public ChainStatusVO status() {
        try {
            String clientVersion = web3j.web3ClientVersion().send().getWeb3ClientVersion();
            BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            return new ChainStatusVO(clientVersion, infuraProperties.getChainId(), latestBlock, gasPrice);
        } catch (Exception e) {
            throw new IllegalStateException("查询链上状态失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Retry(name = "rpc")
    @CircuitBreaker(name = "rpc")
    public BigInteger latestBlockNumber() {
        // 重试/熔断仍挂在本 Bean 方法上（AOP 不拦截静态工具方法），这里只收拢取数与异常转译
        return ChainQueryUtils.latestBlockNumber(web3j);
    }

    @Override
    @Retry(name = "rpc")
    @CircuitBreaker(name = "rpc")
    public BalanceVO getBalance(String address) {
        try {
            BigInteger wei = web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST)
                    .send()
                    .getBalance();
            return new BalanceVO(address, wei, Convert.fromWei(wei.toString(), Convert.Unit.ETHER));
        } catch (Exception e) {
            throw new IllegalStateException("查询地址余额失败: " + e.getMessage(), e);
        }
    }
}
