package com.ray.web3.service;

import com.ray.web3.model.dto.TransferEventDTO;

import java.util.List;

/**
 * 补拉分页落库：一个分页的“事件写入 + 游标推进”在同一事务内提交
 * <p>
 * 事务边界以“页”为单位：拆开会出现“游标前移但事件没入库”的丢单，或“事件入库但游标没动”的重拉；
 * 一页为单元时崩溃最多回退一页，重拉代价可控且幂等
 */
public interface TransferEventStoreService {

    /**
     * 落库一页数据：先幂等批量写入事件，再把游标推进到 pageToBlock（同事务）
     *
     * @param contractAddress  合约地址（大小写不敏感，落库统一小写）
     * @param pageToBlock      本页覆盖到的结束区块（游标推进目标）
     * @param pageEvents       本页解码后的 Transfer 事件（可为空：空页不发 SQL，但照例推进游标）
     * @return 本页实际新增的事件行数（重放区间为 0）
     */
    long savePage(String contractAddress, long pageToBlock, List<TransferEventDTO> pageEvents);
}
