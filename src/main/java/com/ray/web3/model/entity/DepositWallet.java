package com.ray.web3.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 充值地址配置实体（REQ-BIZ-04 模块四）
 * <p>
 * 平台热钱包与用户专属充值地址统一在此维护，路由时按 to_address 小写等值命中；
 * contract 为空表示"任意代币到账即视为充值"，非空表示仅该合约（币种）计入
 */
@Data
@TableName("deposit_wallet")
public class DepositWallet {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 链 ID */
    private Long chainId;

    /** 充值地址（小写） */
    private String address;

    /** 地址类型：HOT=平台热钱包，USER=用户充值地址 */
    private String walletType;

    /** 归属用户，walletType=USER 时非空 */
    private String userId;

    /** 币种符号（展示/对账用） */
    private String currency;

    /** 限定代币合约地址（小写），为空表示不限合约 */
    private String contract;

    /** 1=启用，0=停用 */
    private Integer status;

    private String remark;

    @TableField(update = "now()")
    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}
