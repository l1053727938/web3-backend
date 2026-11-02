package com.ray.web3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ray.web3.model.entity.WithdrawRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 提币记录 Mapper（第 3 周：写与发交易）
 * <p>
 * 写入走 {@code BaseMapper#insert}（INIT 落库）与 {@code BaseMapper#updateById}（状态回填）；
 * 这里只补两个按业务键的读写：单号回读 + 广播结果回填
 */
public interface WithdrawRecordMapper extends BaseMapper<WithdrawRecord> {

    /**
     * 按提币单号回读（幂等查询：用户重复提交相同单号直接返回已有结果，不再铸造新交易）
     */
    @Select("""
            SELECT * FROM withdraw_record WHERE withdraw_no = #{withdrawNo}
            """)
    WithdrawRecord selectByWithdrawNo(@Param("withdrawNo") String withdrawNo);

    /**
     * 广播成功后回填交易哈希并推进状态；仅从 INIT 流转，重复回填返回 0 行防串改
     */
    @Update("""
            UPDATE withdraw_record
            SET transaction_hash = #{transactionHash}, status = #{status}
            WHERE withdraw_no = #{withdrawNo} AND status = 'INIT'
            """)
    int markBroadcast(@Param("withdrawNo") String withdrawNo,
                      @Param("transactionHash") String transactionHash,
                      @Param("status") String status);

    /**
     * 失败落状态并记录原因（估算 revert、节点拒接等），nonce/gas 保留供排查
     */
    @Update("""
            UPDATE withdraw_record
            SET status = 'FAILED', error_message = #{errorMessage}
            WHERE withdraw_no = #{withdrawNo} AND status = 'INIT'
            """)
    int markFailed(@Param("withdrawNo") String withdrawNo,
                   @Param("errorMessage") String errorMessage);
}
