package com.ray.web3.service.impl;

import com.ray.web3.config.DepositProperties;
import com.ray.web3.consts.OutboxConsts;
import com.ray.web3.model.entity.OutboxMessage;
import com.ray.web3.service.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 版 Outbox 投递通道（第 3 周 Day 3-4，默认通道）
 * <p>
 * 未接入 Kafka 时先把 Outbox 消息投递到原来的 {@code deposit_channel}，
 * 保持下游消费方式不变；可靠性由 outbox_message 表保证，Redis 只是传输层。
 * 设置 {@code web3.outbox.transport=kafka} 后本 Bean 不加载，改由
 * {@link KafkaOutboxPublisher} 投递到 Kafka。
 * <p>
 * 只有充值消息有下游通道；未知 bizType 抛错，让投递器走重试/FAILED 流程，而不是静默丢弃。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "web3.outbox.transport", havingValue = "redis", matchIfMissing = true)
public class RedisOutboxPublisher implements OutboxPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final DepositProperties depositProperties;

    @Override
    public void publish(OutboxMessage message) {
        if (!OutboxConsts.BizType.DEPOSIT.equals(message.getBizType())) {
            throw new IllegalArgumentException("尚未配置 Outbox 投递通道: bizType=" + message.getBizType());
        }
        String channel = depositProperties.getDepositChannel();
        stringRedisTemplate.convertAndSend(channel, message.getPayload());
        log.debug("[outbox] 已投递 channel={} bizNo={}", channel, message.getBizNo());
    }
}