package com.ray.web3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ray.web3.model.entity.BlockSyncState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface BlockSyncStateMapper extends BaseMapper<BlockSyncState> {

    /**
     * 游标原子推进：依赖唯一键 uk_chain_contract 做 upsert，
     * 多实例并发补拉同一合约时不会产生"先查后插"的竞态；
     * GREATEST 保证游标只向前推（手动补跑历史区间时不会把游标回退，避免重复处理）
     *
     * @param lastProcessedBlock 该合约已完整处理到的区块高度（下一轮从此块的下一块继续）
     * @return 影响行数（MySQL：插入 1，更新 2，值未变 0）
     */
    @Insert("""
            INSERT INTO block_sync_state (chain_id, contract_address, latest_scanned_block)
            VALUES (#{chainId}, #{contractAddress}, #{lastProcessedBlock})
            ON DUPLICATE KEY UPDATE latest_scanned_block = GREATEST(latest_scanned_block, #{lastProcessedBlock})
            """)
    int upsertCursor(@Param("chainId") Long chainId,
                     @Param("contractAddress") String contractAddress,
                     @Param("lastProcessedBlock") Long lastProcessedBlock);

    /**
     * 读取已落库的游标，无记录返回 null
     */
    @Select("""
            SELECT latest_scanned_block FROM block_sync_state
            WHERE chain_id = #{chainId} AND contract_address = #{contractAddress}
            """)
    Long selectCursor(@Param("chainId") Long chainId,
                      @Param("contractAddress") String contractAddress);
}
