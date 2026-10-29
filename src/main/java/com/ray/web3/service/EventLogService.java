package com.ray.web3.service;

import com.ray.web3.model.dto.TransferEventDTO;
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

    /**
     * 分页拉取指定合约区间内的 Transfer 日志，按事件蓝图完整解码（含 decimals() 动态精度换算）
     * <p>
     * 非 Transfer 事件在解码阶段直接丢弃；返回的 displayValue 与 Etherscan 展示一致
     *
     * @param contractAddress 代币合约地址（0x 开头）
     * @return 按区块升序排列的 Transfer 事件业务对象
     */
    List<TransferEventDTO> fetchDecodedTransferLogs(String contractAddress, BigInteger fromBlock, BigInteger toBlock);

    /**
     * 拉取并解码【单个区块分页】的 Transfer 事件（不做链上高度截断，区间以入参为准）
     * <p>
     * 供历史补拉服务按页推进游标使用：一页拉完立即解码返回，调用方落库后即可推进游标；
     * 若该页命中节点单次结果数上限，内部按更小跨度续拉，保证返回的是整个区间的完整日志
     *
     * @param contractAddress 代币合约地址（0x 开头）
     * @param fromBlock       起始区块（含）
     * @param toBlock         结束区块（含）
     * @return 按区块升序排列的 Transfer 事件业务对象
     */
    List<TransferEventDTO> fetchTransferLogPage(String contractAddress, BigInteger fromBlock, BigInteger toBlock);

    /**
     * 按交易哈希读取收据，解码其中全部 ERC-20 Transfer 事件（精度按各日志合约地址动态获取）
     *
     * @param txHash 交易哈希（0x 开头 64 位 hex）
     * @return 收据内按 logIndex 排列的 Transfer 事件业务对象
     */
    List<TransferEventDTO> fetchTransfersByTx(String txHash);
}
