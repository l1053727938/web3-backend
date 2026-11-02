package com.ray.web3.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigInteger;
import java.time.LocalDateTime;

/**
 * 提币交易记录实体（第 3 周：写与发交易）
 * <p>
 * 生命周期贯穿“构造 → 签名 → 广播 → 追踪 → (替换)”；{@code status} 单向流转见 {@code WithdrawConsts.Status}。
 * 先落库（INIT）再广播，广播结果不确定时依据本行 nonce 上链查询判定，避免盲目重试导致双发
 */
@Data
@TableName("withdraw_record")
public class WithdrawRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 提币单号（对外业务主键，WD 前缀） */
    private String withdrawNo;

    /** 链 ID */
    private Long chainId;

    /** 代币合约地址（小写） */
    private String contractAddress;

    /** 发起方热钱包地址 */
    private String fromAddress;

    /** 提币目标地址 */
    private String toAddress;

    /** 提币金额（最小单位整数字符串，容纳 uint256 全量） */
    private String rawValue;

    /** 本笔交易占用的 Nonce */
    private BigInteger nonce;

    /** 估算并加安全垫后的 gas 上限 */
    private BigInteger gasLimit;

    /** 广播时使用的 gas 单价（wei） */
    private BigInteger gasPrice;

    /** 广播成功后的交易哈希 */
    private String transactionHash;

    /** 提币状态，取值见 {@code WithdrawConsts.Status} */
    private String status;

    /** 失败/拒绝原因 */
    private String errorMessage;

    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}
