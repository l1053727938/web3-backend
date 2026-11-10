package com.ray.web3.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分录明细实体（第 2 周 U1-b）
 * <p>
 * 一个账户一行，条数不定；{@code balance_after} 是这个子账在<b>本笔操作之后</b>的余额，
 * 对账出差异时靠它把“账本 vs 余额”的偏差定位到具体一笔。
 * <p>
 * 只许 INSERT，禁止 UPDATE/DELETE（库账号也不应授予该权限）。
 */
@Data
@TableName("ledger_posting")
public class LedgerPosting {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属分录组 ledger_entry.id */
    private Long entryId;

    /** 账户 ID，重建余额的分组键 */
    private Long accountId;

    /** 方向：DEBIT/CREDIT */
    private String direction;

    /** 金额，永远正数 */
    private BigDecimal amount;

    /** 币种 */
    private String currency;

    /** 本笔操作后该子账余额，审计用 */
    private BigDecimal balanceAfter;

    /** 子账类型：AVAILABLE/FROZEN */
    private String balanceType;

    private LocalDateTime createdAt;
}
