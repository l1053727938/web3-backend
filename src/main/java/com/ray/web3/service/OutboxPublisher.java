package com.ray.web3.service;

import com.ray.web3.model.entity.OutboxMessage;

/**
 * Outbox 消息投递通道（第 3 周 Day 3-4）
 * <p>
 * 投递器只负责“取消息、调通道、改状态”，具体发到哪里由实现决定：
 * 当前项目尚无 Kafka 依赖，{@code RedisOutboxPublisher} 用 Redis 频道作为临时通道；
 * 后续接 Kafka 时新增实现类即可，Outbox 表与投递器无需改动。
 * <p>
 * 实现方失败时直接抛异常，投递器据此累计重试次数并安排退避。
 */
public interface OutboxPublisher {

    /**
     * 投递一条消息；失败抛异常，不能吞掉
     */
    void publish(OutboxMessage message) throws Exception;
}