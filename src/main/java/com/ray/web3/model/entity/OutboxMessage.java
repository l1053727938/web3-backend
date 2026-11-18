package com.ray.web3.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Outbox 消息实体（第 3 周 Day 1-2）
 * <p>
 * 一条待投递消息一行，与业务表（如 {@code deposit_record}）在<b>同一个本地事务</b>里写入：
 * 业务成功则消息一定落库，业务回滚则消息一定不存在，不存在“消息发了业务没成”或“业务成了消息丢了”的中间态。
 * <p>
 * 投递器只扫 {@code status=NEW} 且到达 {@code next_retry_at} 的行；投递成功后置 {@code SENT}，
 * 失败则 {@code retry_count+1} 并按指数退避推进 {@code next_retry_at}，超过上限置 {@code FAILED}。
 * <p>
 * 投递语义是“至少一次”：投递器发成功但改状态前宕机，重启后会再发一次，
 * 重复由消费端 {@code consumer_biz} 幂等表挡住。
 */
@Data
@TableName("outbox_message")
public class OutboxMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型：DEPOSIT/WITHDRAW/MATCH ... */
    private String bizType;

    /** 业务单号（如充值单号），消费端按此幂等 */
    private String bizNo;

    /**
     * Kafka 分区键：同一用户的事件必须使用同一个 key（通常为 userId），
     * 保证进入同一 partition 按序消费；为空时投递器回退 {@link #bizNo}。
     */
    private String partitionKey;

    /** 消息体快照（JSON 字符串），避免投递时业务表已变更 */
    private String payload;

    /** 状态：NEW=待投递，SENT=已投递，FAILED=重试超限 */
    private String status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 下次可投递时间，退避重试落点（由数据库时间维护，避免 JVM/MySQL 时区不一致） */
    private LocalDateTime nextRetryAt;

    private LocalDateTime createdAt;
}