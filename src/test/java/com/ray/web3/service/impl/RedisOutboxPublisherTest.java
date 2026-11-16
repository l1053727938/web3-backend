package com.ray.web3.service.impl;

import com.ray.web3.config.DepositProperties;
import com.ray.web3.consts.OutboxConsts;
import com.ray.web3.model.entity.OutboxMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Redis 临时投递通道单测（第 3 周 Day 3-4）：充值消息发到配置频道；未知业务类型显式失败
 */
@ExtendWith(MockitoExtension.class)
class RedisOutboxPublisherTest {

    private static final String CHANNEL = "deposit_channel";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private RedisOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        DepositProperties properties = new DepositProperties();
        properties.setDepositChannel(CHANNEL);
        publisher = new RedisOutboxPublisher(stringRedisTemplate, properties);
    }

    @Test
    void 充值消息投递到配置的Redis频道() {
        OutboxMessage message = new OutboxMessage();
        message.setBizType(OutboxConsts.BizType.DEPOSIT);
        message.setBizNo("DP1");
        message.setPayload("{\"depositNo\":\"DP1\"}");

        publisher.publish(message);

        verify(stringRedisTemplate).convertAndSend(CHANNEL, message.getPayload());
    }

    @Test
    void 未知业务类型拒绝投递() {
        OutboxMessage message = new OutboxMessage();
        message.setBizType("MATCH");
        message.setPayload("{}");

        assertThatThrownBy(() -> publisher.publish(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MATCH");
        verifyNoInteractions(stringRedisTemplate);
    }
}