package com.ray.web3.service;

import com.ray.web3.model.vo.InternalTransferVO;

import java.util.List;

/**
 * 内部交易追溯服务（需求一）：对给定交易哈希回溯调用树，提取其中所有真实发生的内部 ETH 转账。
 * <p>
 * 定位为充值对账的<b>旁路补偿</b>能力，而非实时监听主链路：ERC-20 转账一定会留 Transfer 日志，
 * 主链路靠日志即可；只有"经过代理/路由合约、最终以内部交易形式到账的 ETH"才需要本服务兜底。
 */
public interface DebugTraceService {

    /**
     * 回溯交易，返回其执行过程中所有 {@code value>0} 的 CALL 内部 ETH 转账 (from, to, value)。
     *
     * @param txHash 交易哈希（0x + 64 位 hex）
     * @return 内部转账列表（前序遍历顺序，地址小写归一）
     */
    List<InternalTransferVO> traceInternalTransfers(String txHash);
}
