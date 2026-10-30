package com.ray.web3.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 充值记录实体（REQ-BIZ-04 模块四，业务闭环落库对象）
 * <p>
 * 幂等靠唯一键 (chain_id, transaction_hash, log_index) + INSERT IGNORE：重连重放、
 * 补拉分页重叠、实时与补拉双写均被数据库约束消化，不依赖分布式事务；
 * 金额列用字符串存十进制（uint256 最多 78 位，MySQL DECIMAL 最大 65 位容不下）
 */
@Data
@TableName("deposit_record")
public class DepositRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 充值单号（对外业务主键） */
    private String depositNo;

    /** 链 ID */
    private Long chainId;

    /** 代币合约地址（小写） */
    private String contractAddress;

    /** 交易哈希（幂等键之一） */
    private String transactionHash;

    /** 日志在区块内的序号（幂等键之一） */
    private Long logIndex;

    /** 入账区块高度 */
    private Long blockNumber;

    /** 转出地址 */
    private String fromAddress;

    /** 转入地址（命中的平台热钱包/用户充值地址） */
    private String toAddress;

    /** 命中地址类型：HOT/USER */
    private String walletType;

    /** 归属用户，walletType=USER 时非空 */
    private String userId;

    /** 币种符号 */
    private String currency;

    /** 原始金额（最小单位整数字符串） */
    private String rawValue;

    /** 换算后的到账金额（十进制字符串） */
    private String displayValue;

    /** 换算所用代币精度 */
    @TableField("`decimals`")
    private Integer decimals;

    /** 充值状态：PENDING_CONFIRM/CREDITED/INVALID */
    private String status;

    /** 达到确认阈值时的区块高度 */
    private Long confirmedBlock;

    /** 事件来源：STREAM/BACKFILL */
    private String eventSource;

    /** 下游通知状态：INIT/SENT/FAILED */
    private String notifyStatus;

    /** 最近一次成功发布时间 */
    private LocalDateTime notifyAt;

    @TableField(update = "now()")
    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}
