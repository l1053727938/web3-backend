package com.ray.web3.service;

import java.math.BigInteger;

/**
 * Gas 估算服务（第 3 周：写与发交易）
 * <p>
 * 封装 eth_estimateGas 与 eth_gasPrice，并叠加安全垫：估算值用于把交易 gas 上限设得"够烧但不至于爆"，
 * gas 价格用于让交易能被打包。<b>估算失败即抛</b>——节点在余额不足、合约 Revert 时会直接报错，
 * 这正是"发之前就知道会失败"的提前拦截，绝不能吞掉异常按估算成功继续广播
 */
public interface GasEstimateService {

    /**
     * eth_estimateGas：估算一次合约调用的 gas 消耗，返回已叠加安全垫上限后的建议 gasLimit。
     * <p>
     * 对 ERC-20 提币：from=热钱包，to=代币合约，data=transfer(...) 编码，value=0
     *
     * @throws IllegalStateException 估算失败（余额不足 / 合约 Revert / 节点错误），调用方据此中止发交易
     */
    BigInteger estimateGasLimit(String from, String to, String data);

    /** eth_gasPrice：当前建议 gas 单价（wei），已按配置上浮一个安全系数以提升打包概率 */
    BigInteger currentGasPrice();
}
