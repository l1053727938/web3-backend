package com.ray.web3.service;

import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;

/**
 * 单段 RPC 调用抽象：重试/熔断按"每个区块分段"粒度生效，
 * 避免标注在分页主编排方法上导致重试时整个拉取从头再来
 */
public interface RpcCallService {

    /**
     * 执行一次 eth_getLogs（带 rpc-segment 重试/熔断）
     *
     * @return 原始 EthLog 响应，业务 error 由调用方判断
     */
    EthLog ethGetLogs(EthFilter filter);
}
