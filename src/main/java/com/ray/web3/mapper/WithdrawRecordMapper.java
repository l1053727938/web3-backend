package com.ray.web3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ray.web3.model.entity.WithdrawRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 提币记录 Mapper（第 3 周：写与发交易）
 * <p>
 * 写入走 {@code BaseMapper#insert}（INIT 落库）与 {@code BaseMapper#updateById}（状态回填）；
 * 这里只补按业务键的读写：单号回读 + 广播结果回填 + Day 3-4 确认追踪的状态扫描与终态回填
 * + Day 5-7 卡单扫描与加速/替换回填
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
     * 加速成功后 {@link #markAccelerated} 会清空它，使本笔能重新接受下一轮标注；
     * 自动加速（Day 5-7）达次数上限、或提价超封顶时也复用本方法留单给人工
     */
    @Update("""
            UPDATE withdraw_record
            SET error_message = #{errorMessage}
            WHERE withdraw_no = #{withdrawNo} AND status = 'BROADCAST' AND error_message IS NULL
            """)
    int markStuck(@Param("withdrawNo") String withdrawNo,
                  @Param("errorMessage") String errorMessage);

    /**
     * 取一批已卡单的 {@code BROADCAST} 记录（Day 5-7 自动加速扫描入口）：比 {@link #selectByStatus}
     * 多一道 {@code created_at <} 门槛，只拾年龄超阈的真卡单，不把刚广播、正常排队交易拉去提价
     * <p>
     * 阈值由应用侧算好后传入（不依赖 {@code DATE_SUB(NOW())} 的参数化写法）：{@code created_at} 是 DB 时钟、
     * 入参是应用时钟，两者偏差远小于卡单阈值的量级（分钟级），不会因此误判；年龄一律用 {@code created_at}
     * 而非已被心跳占用的 {@code updated_at}，但排序仍用 {@code updated_at} 以保持批次轮转公平
     */
    @Select("""
            SELECT * FROM withdraw_record
            WHERE status = #{status} AND created_at < #{createdBefore}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<WithdrawRecord> selectStuck(@Param("status") String status,
                                    @Param("createdBefore") LocalDateTime createdBefore,
                                    @Param("limit") int limit);

    /**
     * 加速重播成功后的原地回填（Day 5-7 RBF）：同 Nonce 新交易替掉内存池里的旧交易，因
     * {@code uk_chain_wallet_nonce} 唯一键不能另插一行，所以是换 {@code transaction_hash}/{@code gas_price}
     * 并计一次 {@code accelerate_count}，状态仍留 BROADCAST（新交易同样待确认）
     * <p>
     * 同时清 {@code error_message}：旧卡单标注已对新交易失效，留着会挡住下一轮 {@link #markStuck}
     * 重新标注（它带 {@code error_message IS NULL} 条件），也会误导排查
     */
    @Update("""
            UPDATE withdraw_record
            SET transaction_hash = #{transactionHash}, gas_price = #{gasPrice},
                accelerate_count = accelerate_count + 1, error_message = NULL
            WHERE withdraw_no = #{withdrawNo} AND status = 'BROADCAST'
            """)
    int markAccelerated(@Param("withdrawNo") String withdrawNo,
                        @Param("transactionHash") String transactionHash,
                        @Param("gasPrice") BigInteger gasPrice);

    /**
     * 本笔提币交易作废转 REPLACED（Day 5-7）：用于“同 Nonce 已被另一笔交易消耗”——无论是节点外
     * 替换、还是本系统主动发撤销空交易覆盖，本笔都再不可能上链执行
     * <p>
     * 刻意<b>不改</b> {@code transaction_hash}：该字段始终代指“本行原提币交易”；替换者/撤销者的哈希
     * 写进 {@code error_message} 文本。若把空交易哈希覆盖上来，追踪会拿它的成功收据把提币误写成 SUCCESS
     */
    @Update("""
            UPDATE withdraw_record
            SET status = 'REPLACED', error_message = #{errorMessage}
            WHERE withdraw_no = #{withdrawNo} AND status = 'BROADCAST'
            """)
    int markReplaced(@Param("withdrawNo") String withdrawNo,
                     @Param("errorMessage") String errorMessage);
}
