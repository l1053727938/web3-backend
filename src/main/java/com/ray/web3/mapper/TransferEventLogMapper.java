package com.ray.web3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ray.web3.model.entity.TransferEventLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Transfer 事件流水写入/对账 Mapper
 * <p>
 * 写入走 INSERT IGNORE + 唯一键 (chain_id, transaction_hash, log_index)：重放安全（幂等）靠数据库约束，
 * 补拉崩溃后重拉同一页不会产生重复行；调用方按 500 行一批切分，避免单语句超出 max_allowed_packet
 */
public interface TransferEventLogMapper extends BaseMapper<TransferEventLog> {

    /**
     * 批量幂等写入：命中唯一键的重复行被 IGNORE 跳过
     *
     * @return 实际新增行数（重复被忽略的不计入）
     */
    @Insert("""
            <script>
            INSERT IGNORE INTO transfer_event_log
                (chain_id, contract_address, block_number, transaction_hash, log_index,
                 from_address, to_address, raw_value, display_value, `decimals`)
            VALUES
            <foreach collection="list" item="e" separator=",">
                (#{e.chainId}, #{e.contractAddress}, #{e.blockNumber}, #{e.transactionHash}, #{e.logIndex},
                 #{e.fromAddress}, #{e.toAddress}, #{e.rawValue}, #{e.displayValue}, #{e.decimals})
            </foreach>
            </script>
            """)
    int insertIgnoreBatch(@Param("list") List<TransferEventLog> list);

    /**
     * 对账计数：统计该合约在 [fromBlock, toBlock] 区间内已落库的 Transfer 条数，与链上拉取条数比对确认不漏单
     */
    @Select("""
            SELECT COUNT(*) FROM transfer_event_log
            WHERE chain_id = #{chainId} AND contract_address = #{contractAddress}
              AND block_number BETWEEN #{fromBlock} AND #{toBlock}
            """)
    long countInRange(@Param("chainId") Long chainId,
                      @Param("contractAddress") String contractAddress,
                      @Param("fromBlock") long fromBlock,
                      @Param("toBlock") long toBlock);
}
