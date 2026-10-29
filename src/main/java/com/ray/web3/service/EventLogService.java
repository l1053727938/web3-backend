package com.ray.web3.service;

import com.ray.web3.model.vo.TransferLogVO;

import java.math.BigInteger;
import java.util.List;

public interface EventLogService {

    /**
     * 分页拉取指定合约在 [fromBlock, toBlock] 区间内的全部 ERC-20 Transfer 日志
     * <p>
     * RPC 节点（如 Infura）对单次 eth_getLogs 的区块跨度有限制，
     * 内部按固定块跨度分段循环请求，直到覆盖整个区间
     *
     * @param contractAddress 合约地址（0x 开头）
     * @param fromBlock       起始区块（含）
     * @param toBlock         结束区块（含），超过当前链上高度时自动截断
     * @return 按区块升序排列的 Transfer 日志
     */
    List<TransferLogVO> fetchTransferLogs(String contractAddress, BigInteger fromBlock, BigInteger toBlock);
}
