package com.ray.web3.service.impl;

import com.ray.web3.config.OutboxProperties;
import com.ray.web3.model.entity.OutboxMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka 投递通道单测（第 3 周 Day 5-6）：key=partitionKey（userId），为空回退 bizNo；broker 失败抛异常供投递器退避
 */
@ExtendWith(MockitoExtension.class)
class KafkaOutboxPublisherTest {

    private static final String TOPIC = "deposit-outbox";
    private static final String KEY = "DP1";
    private static final String USER_ID = "10001";
    private static final String PAYLOAD = "{\"depositNo\":\"DP1\"}";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        OutboxProperties properties = new OutboxProperties();
        properties.getKafka().setTopic(TOPIC);
        properties.getKafka().setSendTimeoutSeconds(1);
        publisher = new KafkaOutboxPublisher(kafkaTemplate, properties);
    }

    @Test
    void 有分区键时按userId发送保证同用户进同一分区() {
        when(kafkaTemplate.send(TOPIC, USER_ID, PAYLOAD))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        publisher.publish(message(USER_ID));

        verify(kafkaTemplate).send(TOPIC, USER_ID, PAYLOAD);
    }

    @Test
    void 分区键为空时回退业务单号做key() {
        when(kafkaTemplate.send(TOPIC, KEY, PAYLOAD))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        publisher.publish(message());

        verify(kafkaTemplate).send(TOPIC, KEY, PAYLOAD);
    }

    @Test
    void broker失败时抛异常供投递器进入退避重试() {
        when(kafkaTemplate.send(TOPIC, KEY, PAYLOAD))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> publisher.publish(message()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka 投递失败");
    }

    private static OutboxMessage message() {
        return message(null);
    }

    private static OutboxMessage message(String partitionKey) {
        OutboxMessage message = new OutboxMessage();
        message.setId(1L);
        message.setBizType("DEPOSIT");
        message.setBizNo(KEY);
        message.setPartitionKey(partitionKey);
        message.setPayload(PAYLOAD);
        message.setStatus("NEW");
        message.setRetryCount(0);
        return message;
    }
}