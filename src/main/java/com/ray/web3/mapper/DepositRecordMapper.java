package com.ray.web3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ray.web3.model.entity.DepositRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 充值记录写入 Mapper（REQ-BIZ-04）
 * <p>
 * 写入走 INSERT IGNORE + 唯一键 (chain_id, transaction_hash, log_index)：重连重放、补拉分页重叠、
 * 实时与补拉双写均被数据库约束消化，返回值即"本次真正新增的入账条数"；
 * 只有新增行才发布 deposit_channel，避免重复通知引起重复记账。
 * 通知状态回写走 {@code BaseMapper#updateById} 的部分字段更新（只改 notify_status/notify_at）
 */
public interface DepositRecordMapper extends BaseMapper<DepositRecord> {

    /**
     * 批量幂等写入充值记录：命中唯一键 (chain_id, transaction_hash, log_index) 的重复行被 IGNORE 跳过
     *
     * @return 实际新增行数（重放/重叠为 0）
     */
    @Insert("""
            <script>
            INSERT IGNORE INTO deposit_record
                (deposit_no, chain_id, contract_address, transaction_hash, log_index, block_number,
                 from_address, to_address, wallet_type, user_id, currency,
                 raw_value, display_value, `decimals`, status, event_source, notify_status)
            VALUES
            <foreach collection="list" item="e" separator=",">
                (#{e.depositNo}, #{e.chainId}, #{e.contractAddress}, #{e.transactionHash}, #{e.logIndex}, #{e.blockNumber},
                 #{e.fromAddress}, #{e.toAddress}, #{e.walletType}, #{e.userId}, #{e.currency},
                 #{e.rawValue}, #{e.displayValue}, #{e.decimals}, #{e.status}, #{e.eventSource}, #{e.notifyStatus})
            </foreach>
            </script>
            """)
    int insertIgnoreBatch(@Param("list") List<DepositRecord> list);

    /**
     * 幂等回读：按幂等键取已存在的充值记录，无则返回 null
     */
    @Select("""
            SELECT * FROM deposit_record
            WHERE chain_id = #{chainId} AND transaction_hash = #{transactionHash} AND log_index = #{logIndex}
            """)
    DepositRecord selectByTxLog(@Param("chainId") Long chainId,
                                @Param("transactionHash") String transactionHash,
                                @Param("logIndex") Long logIndex);
}
