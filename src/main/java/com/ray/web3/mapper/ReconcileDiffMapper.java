package com.ray.web3.mapper;

import com.ray.web3.model.entity.ReconcileDiff;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账差异表 Mapper（第 5 周 Day 3-4）
 * <p>
 * 每次跑批先按 {@code report_date} 删除再整批重建：同一窗口重复执行结果一致，
 * 不在旧结果上累加，也不依赖“差异状态”做增量。
 */
public interface ReconcileDiffMapper {

    /** 重建前清空该报告日的旧结果 */
    @Delete("DELETE FROM reconcile_diff WHERE report_date = #{reportDate}")
    int deleteByDate(@Param("reportDate") LocalDate reportDate);

    /** 批量写入差异明细；单批由 ReconcileService 按 DbConsts.INSERT_BATCH_SIZE 切分 */
    @Insert("""
            <script>
            INSERT INTO reconcile_diff
                (report_date, biz_type, biz_no, diff_type, report_group, direction,
                 chain_amount, ledger_amount, delta, currency, evidence, status)
            VALUES
            <foreach collection="list" item="d" separator=",">
                (#{d.reportDate}, #{d.bizType}, #{d.bizNo}, #{d.diffType}, #{d.reportGroup}, #{d.direction},
                 #{d.chainAmount}, #{d.ledgerAmount}, #{d.delta}, #{d.currency}, #{d.evidence}, #{d.status})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<ReconcileDiff> list);

    /** 查某报告日的差异明细，供日报/接口使用 */
    @Select("""
            SELECT * FROM reconcile_diff
            WHERE report_date = #{reportDate}
            ORDER BY report_group, diff_type, biz_no
            """)
    List<ReconcileDiff> selectByDate(@Param("reportDate") LocalDate reportDate);
}
