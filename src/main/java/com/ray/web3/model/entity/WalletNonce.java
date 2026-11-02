package com.ray.web3.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigInteger;
import java.time.LocalDateTime;

/**
 * 钱包 Nonce 分配游标实体（第 3 周：写与发交易）
 * <p>
 * 每 (chain_id, address) 一行，next_nonce = 该钱包下一个待分配 Nonce；
 * 分配动作必须在事务内 SELECT ... FOR UPDATE 锁行后执行，见 NonceManagerService
 */
@Data
@TableName("wallet_nonce")
public class WalletNonce {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 链 ID */
    private Long chainId;

    /** 钱包地址（小写），即交易发送方 */
    private String address;

    /** 下一个待分配的 Nonce（分配即推进） */
    private BigInteger nextNonce;

    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}
