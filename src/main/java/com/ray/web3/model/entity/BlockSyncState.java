package com.ray.web3.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 区块扫描进度表
 */
@Data
@TableName("block_sync_state")
public class BlockSyncState {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 链 ID */
    private Long chainId;

    /** 合约地址，空串表示原生链扫描 */
    private String contractAddress;

    /** 已扫描到的区块高度 */
    private Long latestScannedBlock;

    @TableField(update = "now()")
    private LocalDateTime updatedAt;
}
