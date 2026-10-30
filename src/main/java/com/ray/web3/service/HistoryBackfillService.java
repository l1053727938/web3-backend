package com.ray.web3.service;

import com.ray.web3.model.vo.BackfillResultVO;

import java.math.BigInteger;

/**
 * 历史数据分页拉取服务（REQ-SYNC-02，模块二）
 * <p>
 * 基于 eth_getLogs 的历史日志补拉，作为监听器启动时的“状态补齐”：从游标（lastProcessedBlock）断点续传，
 * 按小跨度分页拉取 → 调用模块一解码器转换 → 每页“事件落库 + 游标推进”同事务，确保不漏单、崩溃可续、重放幂等
 */
public interface HistoryBackfillService {

    /**
     * 补拉指定合约 [fromBlock, toBlock] 区间内的全部 Transfer 日志并落库
     *
     * @param contractAddress 代币合约地址（0x 开头）
     * @param fromBlock       期望起始区块（含）；若已有游标且 &ge; fromBlock，则自动从游标 +1 续拉
     * @param toBlock         期望结束区块（含）；超过链上高度自动截断，受 maxBlocksPerRun 限幅
     * @param chunkSize       单页区块跨度；为空取 web3.backfill.chunk-size（默认 1000）
     * @return 本次补拉结果（实际区间、页数、事件数、游标、是否受限提前收尾）
     */
    BackfillResultVO backfill(String contractAddress, BigInteger fromBlock, BigInteger toBlock, Integer chunkSize);

    /**
     * 查询该合约当前补拉游标（lastProcessedBlock），无记录返回 null
     */
    Long currentCursor(String contractAddress);

    /**
     * 对账：统计 [fromBlock, toBlock] 区间内 transfer_event_log 已落库的 Transfer 条数
     */
    Long savedTransferCount(String contractAddress, BigInteger fromBlock, BigInteger toBlock);
}
