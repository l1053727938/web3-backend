package com.ray.web3.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户主表实体（第 3 周：用户业务接入）
 * <p>
 * 只承载身份：余额在 {@code account}、充值地址在 {@code deposit_wallet}，
 * 不让一张表同时长成“用户 + 钱包 + 资产”，否则任何一方演进都要改用户主表。
 */
@Data
@TableName("`user`")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，全局唯一 */
    private String username;

    /** 1=正常，0=禁用 */
    private Integer status;

    private LocalDateTime createdAt;
}
