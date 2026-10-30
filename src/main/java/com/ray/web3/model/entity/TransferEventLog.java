package com.ray.web3.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ERC-20 Transfer 事件流水表实体
 * <p>
 * 历史补拉每一页“事件落库 + 游标推进”同事务提交，保证不漏单；
 * 金额列用变长字符串存十进制（uint256 最多 78 位，MySQL DECIMAL 最大 65 位容不下）
 */
@Data
@TableName("transfer_event_log")
public class TransferEventLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 链 ID */
    private Long chainId;

    /** 代币合约地址（小写） */
    private String contractAddress;

    /** 区块高度 */
    private Long blockNumber;

    /** 交易哈希 */
    private String transactionHash;

    /** 日志在区块内的序号 */
    private Long logIndex;

    /** 转出地址 */
    private String fromAddress;

    /** 转入地址 */
    private String toAddress;

    /** 原始金额（最小单位整数字符串） */
    private String rawValue;

    /** 按代币精度换算后的金额（十进制字符串，保留尾零） */
    private String displayValue;

    /** 换算所用代币精度 */
    @TableField("`decimals`")
    private Integer decimals;

    @TableField(update = "now()")
    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}
