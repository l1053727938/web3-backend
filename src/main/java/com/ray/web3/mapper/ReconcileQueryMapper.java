package com.ray.web3.mapper;

import com.ray.web3.model.entity.DepositRecord;
import com.ray.web3.model.vo.ChainOrphanRow;
import com.ray.web3.model.vo.LedgerAggRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账取数 Mapper（第 5 周 Day 3-4）
 * <p>
 * 三方数据源：
 * <ol>
 *   <li>链上事实 {@code transfer_event_log}（经 {@code deposit_wallet} 过滤到平台地址）；</li>
 *   <li>业务桥 {@code deposit_record}（带 decimals / raw_value 快照）；</li>
 *   <li>账本 {@code ledger_entry + ledger_posting}（只取用户侧 AVAILABLE+CREDIT 腿）。</li>
 * </ol>
 * 全部只读；生产应把本 Mapper 指到只读从库（第 5 周 Day 5-6 再做数据源切换）。
 */
public interface ReconcileQueryMapper {

    /** 某日窗口内的充值记录（按入库时间切窗，边界左闭右开） */
    @Select("""
            SELECT * FROM deposit_record
            WHERE created_at >= #{from} AND created_at < #{to}
            ORDER BY id
            """)
    List<DepositRecord> selectDepositByCreatedAt(@Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

    /**
     * 按业务单号批量聚合账本侧金额。
     * <p>
     * 每组只保留用户侧 {@code AVAILABLE + CREDIT} 一条腿；{@code reversal_count} 用于判断
     * 该组是否已被冲正（重组回滚后应排除已冲正组）。
     */
    @Select("""
            <script>
            SELECT le.id AS entry_id,
                   le.biz_no AS biz_no,
                   (SELECT COUNT(*) FROM ledger_entry r
                     WHERE r.biz_type = 'REVERSE' AND r.reverse_of = le.id) AS reversal_count,
                   COALESCE(SUM(CASE WHEN lp.balance_type = 'AVAILABLE' AND lp.direction = 'CREDIT'
                                     THEN lp.amount ELSE 0 END), 0) AS user_amount,
                   MAX(lp.currency) AS currency
            FROM ledger_entry le
            LEFT JOIN ledger_posting lp ON lp.entry_id = le.id
            WHERE le.biz_type = 'DEPOSIT'
              AND le.biz_no IN
              <foreach collection="bizNos" item="b" open="(" separator="," close=")">#{b}</foreach>
            GROUP BY le.id, le.biz_no
            </script>
            """)
    List<LedgerAggRow> selectLedgerAgg(@Param("bizNos") List<String> bizNos);

    /**
     * 链上有、充值记录无的孤儿事件（命中平台地址但没有 deposit_record）。
     * <p>
     * 用 {@code deposit_wallet} 过滤收款地址；配置中心热钱包若没有落库，不在此查询范围内，
     * 由第 5 周 Day 5-6 的地址集合统一并入。
     */
    @Select("""
            SELECT te.chain_id AS chain_id,
                   te.contract_address AS contract_address,
                   te.transaction_hash AS transaction_hash,
                   te.log_index AS log_index,
                   te.to_address AS to_address,
                   te.raw_value AS raw_value,
                   te.display_value AS display_value,
                   te.decimals AS decimals
            FROM transfer_event_log te
            JOIN deposit_wallet dw
              ON dw.chain_id = te.chain_id
             AND dw.address = LOWER(te.to_address)
            LEFT JOIN deposit_record dr
              ON dr.chain_id = te.chain_id
             AND dr.transaction_hash = te.transaction_hash
             AND dr.log_index = te.log_index
            WHERE te.created_at >= #{from} AND te.created_at < #{to}
              AND dr.id IS NULL
            """)
    List<ChainOrphanRow> selectChainOrphans(@Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);
}
