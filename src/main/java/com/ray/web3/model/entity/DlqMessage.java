package com.ray.web3.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 死信消息实体（第 4 周 Day 1-2）
 * <p>
 * 重试达到上限的消息在这里留档，<b>原 outbox_message 不删除</b>（置 FAILED）：
 * 死信表回答“这条为什么失败、失败了几次”，原表保留完整生命周期，两边可互相核对。
 * <p>
 * {@code (source_table, source_id)} 唯一：同一原消息只会有一条死信，重复处理不产生多条。
 */
@Data
@TableName("dlq_message")
public class DlqMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源表，当前固定 outbox_message */
    private String sourceTable;

    /** 原消息 id */
    private Long sourceId;

    /** 业务类型：DEPOSIT/WITHDRAW/MATCH ... */
    private String bizType;

    /** 业务单号，人工按此找回业务记录 */
    private String bizNo;

    /** 原消息分区键（Kafka 场景用于定位 partition） */
    private String partitionKey;

    /** 原消息体快照 */
    private String payload;

    /** 转移时的累计失败次数 */
    private Integer retryCount;

    /** 最后一次失败原因（截断到 512 字符） */
    private String errorMessage;

    /** OPEN=待处理，HANDLED=已处理，IGNORED=确认忽略 */
    private String status;

    private LocalDateTime createdAt;
}
