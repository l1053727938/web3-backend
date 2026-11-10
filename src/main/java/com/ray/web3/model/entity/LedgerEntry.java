package com.ray.web3.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分录组实体（第 2 周 U1-b）
 * <p>
 * 一笔业务一行，幂等键 {@code (biz_type, biz_no)} 在组上：重复投递撞唯一键即整组跳过。
 * <p>
 * <b>只许 INSERT，不许 UPDATE/DELETE</b>：冲正靠新增一组反向分录并让 {@code reverse_of} 指向本行，
 * 原分录永远保留。这是“账本”与“流水表”的分水岭。
 */
@Data
@TableName("ledger_entry")
public class LedgerEntry {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 账本单号，全局唯一 */
    private String entryNo;

    /** 业务类型：DEPOSIT/TRANSFER/FREEZE/FEE/REVERSE */
    private String bizType;

    /** 业务单号，与 bizType 联合幂等 */
    private String bizNo;

    /** 状态：SUCCESS/REVERSED */
    private String status;

    /** 冲正时指向原 entry_id，原分录不改 */
    private Long reverseOf;

    private LocalDateTime createdAt;
}
