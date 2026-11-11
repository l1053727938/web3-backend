package com.ray.web3.ledger.mapper;

import com.ray.web3.model.entity.LedgerPosting;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分录明细 Mapper（第 2 周 U1-b）
 * <p>
 * 一组分录的明细条数不定（2 条、4 条甚至更多），逐条 insert 会多出 N 次网络往返；
 * 这里用单条多值 INSERT 一次落库，仍与分录组在同一个事务里。
 * <p>
 * 同 {@link LedgerEntryMapper}：<b>不继承 BaseMapper</b>，接口上没有 update/delete，
 * 账本明细只能追加，不能被改写。
 * <p>
 * 本包由 {@code LedgerDataSourceConfig} 扫描，走账本专属连接池。
 */
public interface LedgerPostingMapper {

    /**
     * 批量写入分录明细；调用方须保证 list 非空（分录组至少两条 posting）
     */
    @Insert("""
            <script>
            INSERT INTO ledger_posting
                (entry_id, account_id, direction, amount, currency, balance_type, balance_after)
            VALUES
            <foreach collection="list" item="p" separator=",">
                (#{p.entryId}, #{p.accountId}, #{p.direction}, #{p.amount}, #{p.currency},
                 #{p.balanceType}, #{p.balanceAfter})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<LedgerPosting> list);

    /** 按分录组读明细（对账/排障用），按写入顺序返回 */
    @Select("""
            SELECT id, entry_id, account_id, direction, amount, currency, balance_type, balance_after, created_at
            FROM ledger_posting
            WHERE entry_id = #{entryId}
            ORDER BY id
            """)
    List<LedgerPosting> selectByEntryId(@Param("entryId") Long entryId);
}
