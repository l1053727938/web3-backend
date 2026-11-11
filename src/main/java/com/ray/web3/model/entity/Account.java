package com.ray.web3.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账户余额实体（第 2 周 U1：复式账本的余额物化）
 * <p>
 * 一行 = 一个用户 × 一个账户类型 × 一个币种，是 {@code ledger_posting} 的聚合结果，
 * <b>不是账本本身</b>：删掉本表可以用分录重建，反过来不成立。
 * <p>
 * {@code total} 是数据库生成列（available + frozen），应用永远不写它，因此这里标 {@code exist = false}
 * 不参与 ORM 的插入与读取。
 * <p>
 * 余额更新必须走 {@link com.ray.web3.ledger.mapper.AccountMapper#updateBalance} 的
 * {@code version = version + 1 WHERE version = ?} CAS，禁止“读-改-写回”的裸 UPDATE。
 */
@Data
@TableName("`account`")
public class Account {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 账户类型：FUND/SPOT/PERP */
    private String accountType;

    /** 币种符号：USDT/ETH/BTC */
    private String currency;

    /** 可用余额 */
    private BigDecimal available;

    /** 冻结余额 */
    private BigDecimal frozen;

    /** 总额 = available + frozen，数据库生成列，只读 */
    @TableField(exist = false)
    private BigDecimal total;

    /** 乐观锁版本号，每次余额变动 +1 */
    private Long version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
