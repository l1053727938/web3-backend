package com.ray.web3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ray.web3.model.entity.WithdrawRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 提币记录 Mapper（第 3 周：写与发交易）
 * <p>
 * 写入走 {@code BaseMapper#insert}（INIT 落库）与 {@code BaseMapper#updateById}（状态回填）；
 * 这里只补按业务键的读写：单号回读 + 广播结果回填 + Day 3-4 确认追踪的状态扫描与终态回填
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

    /**
     * 按状态取一批待追踪记录（Day 3-4 确认追踪扫描入口）：命中 {@code idx_status (status, updated_at)}，
     * 按 {@code updated_at} 升序优先处理广播最久的一笔，limit 限单轮批量防 RPC 配额被一次打爆
     * <p>
     * ⚠ 本排序隐含一个活性约定：未跃迁的记录必须由追踪方调 {@link #touchBroadcast} 回写
     * {@code updated_at}，否则卡单因状态不变、时间戳不前进而永久霸占队头，满 limit 后会把新交易的
     * 确认推进完全饿死（心跳不是可选优化，而是本查询能持续正常工作的前提）
     */
    @Select("""
            SELECT * FROM withdraw_record
            WHERE status = #{status}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<WithdrawRecord> selectByStatus(@Param("status") String status, @Param("limit") int limit);

    /**
     * 确认达标回填 SUCCESS；仅从 BROADCAST 流转，重复回填返回 0 行（并发追踪/重跑天然幂等）。
     * 同时清 {@code error_message}：该字段可能存有追踪中途写的"卡单待处理"临时备注，
     * 交易既然已确认达标，留着它会误导排查（真正的失败原因走 {@link #markReverted} 覆写）
     */
    @Update("""
            UPDATE withdraw_record
            SET status = 'SUCCESS', error_message = NULL
            WHERE withdraw_no = #{withdrawNo} AND status = 'BROADCAST'
            """)
    int markSuccess(@Param("withdrawNo") String withdrawNo);

    /**
     * 上链后 Revert（receipt.status=0x0）回填 FAILED：与 {@link #markFailed} 的区别在起点状态是 BROADCAST
     * （已成功广播、只是执行失败），error_message 记录收据里的 revertReason
     */
    @Update("""
            UPDATE withdraw_record
            SET status = 'FAILED', error_message = #{errorMessage}
            WHERE withdraw_no = #{withdrawNo} AND status = 'BROADCAST'
            """)
    int markReverted(@Param("withdrawNo") String withdrawNo,
                     @Param("errorMessage") String errorMessage);

    /**
     * 追踪心跳：本轮未得出终态的 BROADCAST 记录回写 {@code updated_at} 使其排到队尾。
     * <p>
     * 只动时间戳不动 status，因此不穿越状态机；作用是给 {@link #selectByStatus} 的 {@code updated_at ASC}
     * 提供轮转公平，防卡单固定占据队头把批次名额吃光。代价是 {@code updated_at} 从"最后状态变更时间"
     * 变为"最后观察时间"，记录年龄一律改用 {@code created_at} 计算，不得再拿 {@code updated_at} 推算
     */
    @Update("""
            UPDATE withdraw_record
            SET updated_at = NOW()
            WHERE withdraw_no = #{withdrawNo} AND status = 'BROADCAST'
            """)
    int touchBroadcast(@Param("withdrawNo") String withdrawNo);

    /**
     * 卡单首次标注：仅写 {@code error_message}（附诊断结果），<b>不改 status</b>——结果仍不确定时不能落终态。
     * <p>
     * {@code AND error_message IS NULL} 保证每笔只标注一次，不会被每轮心跳重复写库；
     * 后续自动加速（Day 5-7）或人工处置可直接拿这个字段当任务队列表识卡单
     */
    @Update("""
            UPDATE withdraw_record
            SET error_message = #{errorMessage}
            WHERE withdraw_no = #{withdrawNo} AND status = 'BROADCAST' AND error_message IS NULL
            """)
    int markStuck(@Param("withdrawNo") String withdrawNo,
                  @Param("errorMessage") String errorMessage);
}
