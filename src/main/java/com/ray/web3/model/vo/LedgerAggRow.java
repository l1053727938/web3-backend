package com.ray.web3.model.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 账本侧聚合行（第 5 周 Day 3-4 对账用）
 * <p>
 * 一行 = 一组 DEPOSIT 分录，{@code userAmount} 只取用户侧
 * {@code AVAILABLE + CREDIT} 那条腿；{@code reversalCount > 0} 表示该组已被冲正。
 */
@Data
public class LedgerAggRow {

    private Long entryId;

    private String bizNo;

    /** 指向本组的冲正组数量（0=未冲正） */
    private Integer reversalCount;

    /** 用户侧可用余额增加额；已冲正组在服务层排除 */
    private BigDecimal userAmount;

    private String currency;
}
