package com.ray.web3.service.impl;

import com.ray.web3.config.WithdrawProperties;
import com.ray.web3.service.GasEstimateService;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthGasPrice;

import java.math.BigInteger;

/**
 * Gas 估算服务实现（第 3 周：写与发交易）
 * <p>
 * 两段式安全垫：估算值与单价都按配置的千分比（Basis Point ×100 口径的 1/1000）上浮后再封顶，
 * 网络瞬时波动交给系数、估算逻辑异常放大交给封顶——两道闸门各挡一类风险
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "web3.withdraw", name = "enabled", havingValue = "true")
public class GasEstimateServiceImpl implements GasEstimateService {

    private static final BigInteger BPS_BASE = BigInteger.valueOf(1000L);

    private final Web3j web3j;
    private final WithdrawProperties properties;

    @Override
    @Retry(name = "rpc")
    public BigInteger estimateGasLimit(String from, String to, String data) {
        // 估算入参不带 nonce/gas 字段：节点按"当前状态执行一次"的口径模拟，多余字段反而干扰模拟环境
        EthEstimateGas response;
        try {
            response = web3j.ethEstimateGas(
                    Transaction.createEthCallTransaction(from, to, data)).send();
        } catch (Exception e) {
            // 网络级异常交 @Retry 兜底重试；重试耗尽走到这里，语义与其他 RPC 失败一致
            throw new IllegalStateException("eth_estimateGas 请求失败: " + e.getMessage(), e);
        }
        if (response.hasError()) {
            // 关键拦截点：余额不足 / 合约 Revert 都从这里浮出（如 "execution reverted: ERC20: insufficient balance"）。
            // 刻意保留节点原文——重放原因对排查有直接价值，也供第 3-4 天 eth_call 深挖 revert data 复用同一口径
            throw new IllegalStateException("Gas 估算失败（余额不足或合约将 Revert）: "
                    + response.getError().getMessage());
        }
        long bps = Math.max(properties.getGasLimitBps(), BPS_BASE.longValue());
        BigInteger padded = response.getAmountUsed().multiply(BigInteger.valueOf(bps))
                .divide(BPS_BASE);
        BigInteger capped = padded.min(properties.getMaxGasLimit());
        log.debug("[withdraw] gas 估算 raw={} padded={} capped={}",
                response.getAmountUsed(), padded, capped);
        return capped;
    }

    @Override
    @Retry(name = "rpc")
    public BigInteger currentGasPrice() {
        try {
            EthGasPrice response = web3j.ethGasPrice().send();
            long bps = Math.max(properties.getGasPriceBps(), BPS_BASE.longValue());
            return response.getGasPrice().multiply(BigInteger.valueOf(bps)).divide(BPS_BASE);
        } catch (Exception e) {
            throw new IllegalStateException("eth_gasPrice 请求失败: " + e.getMessage(), e);
        }
    }
}
