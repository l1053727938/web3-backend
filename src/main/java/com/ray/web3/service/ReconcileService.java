package com.ray.web3.service;

import com.ray.web3.model.entity.ReconcileDiff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 对账任务（第 5 周 Day 3-4）
 * <p>
 * 按日窗口拉取链上事实、充值桥表与账本分录，判定六类差错并落到 {@code reconcile_diff}。
 * 同一报告日重复执行会重建结果（先删后插），不做增量累加。
 */
public interface ReconcileService {

    /**
     * 对指定报告日执行对账
     *
     * @param reportDate 报告日（按 {@code deposit_record.created_at} 切窗，左闭右开）
     */
    ReconcileReport run(LocalDate reportDate);

    /** 查询某报告日的差异明细 */
    List<ReconcileDiff> list(LocalDate reportDate);

    /**
     * @param reportDate   报告日
     * @param total        差异总条数
     * @param inTransit    在途条数（时间差 + 重组回滚）
     * @param overShort    长短款条数（遗漏 + 重复 + 精度差）
     * @param undetermined 待定性条数（未知代币等）
     * @param netDelta     所有差异的 delta 之和（带符号）
     */
    record ReconcileReport(LocalDate reportDate, int total, int inTransit, int overShort,
                           int undetermined, BigDecimal netDelta) {
    }
}
