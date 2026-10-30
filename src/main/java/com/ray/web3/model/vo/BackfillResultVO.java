package com.ray.web3.model.vo;

/**
 * 历史补拉单次调用结果（验收：区间、事件数、游标推进一目了然）
 *
 * @param contract         代币合约地址（小写）
 * @param startBlock       本次实际起始块（已结合断点游标推算，可能大于入参 fromBlock）
 * @param endBlock         本次实际结束块（已按链上高度截断并受 maxBlocksPerRun 限幅）
 * @param pages            本次推进的分页（区块段）数
 * @param fetchedCount     本次拉取并解码到的 Transfer 事件条数
 * @param savedCount       本次实际写库新增条数（重放区间时为 0）
 * @param lastProcessedBlock 补拉完成后该合约的最新游标（下一轮从此块的下一块继续）
 * @param limited          是否因 maxBlocksPerRun 提前收尾（true 时用同一区间重调即可续拉）
 */
public record BackfillResultVO(
        String contract,
        long startBlock,
        long endBlock,
        int pages,
        long fetchedCount,
        long savedCount,
        long lastProcessedBlock,
        boolean limited) {
}
