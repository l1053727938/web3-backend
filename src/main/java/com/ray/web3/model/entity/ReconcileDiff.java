package com.ray.web3.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账差异明细（第 5 周 Day 3-4）
 * <p>
 * 一行 = 一个业务键上的一条差异；同一报告日重跑时按 {@code uk_date_biz_type} 覆盖重建，
 * 不做增量累加，保证同一窗口多次执行结果一致。
 */
@Data
@TableName("reconcile_diff")
public class ReconcileDiff {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对账报告日 */
    private LocalDate reportDate;

    /** 业务类型，当前固定 DEPOSIT */
    private String bizType;

    /** 业务单号；无充值记录的链上孤儿为 CHAIN:tx:log */
    private String bizNo;

    /** 六类差错之一 */
    private String diffType;

    /** 三类报表口径 */
    private String reportGroup;

    /** LONG=长款（账>链），SHORT=短款（账<链），在途/待定可为空 */
    private String direction;

    /** 链上应入账金额 */
    private BigDecimal chainAmount;

    /** 账本已入账金额 */
    private BigDecimal ledgerAmount;

    /** ledger_amount - chain_amount */
    private BigDecimal delta;

    private String currency;

    /** 判定依据：快照 decimals、当前 decimals、重复组数等 */
    private String evidence;

    /** OPEN/RESOLVED/IGNORED */
    private String status;

    private LocalDateTime createdAt;
}
