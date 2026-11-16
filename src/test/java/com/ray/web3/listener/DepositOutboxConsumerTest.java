package com.ray.web3.listener;

import com.ray.web3.service.impl.DepositCreditService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka 消费者单测（第 3 周 Day 5-6）：成功/重复都 ack，失败不 ack
 */
@ExtendWith(MockitoExtension.class)
class DepositOutboxConsumerTest {

    private static final String PAYLOAD = "{\"depositNo\":\"DP1\"}";

    @Mock
    private DepositCreditService depositCreditService;
    @Mock
    private Acknowledgment acknowledgment;

    private DepositOutboxConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new DepositOutboxConsumer(depositCreditService);
    }

    @Test
    void 首次消费成功ack() {
        when(depositCreditService.credit(PAYLOAD))
                .thenReturn(new DepositCreditService.CreditResult("DP1", "LE1", false));

        consumer.onDeposit(record(), acknowledgment);

        verify(acknowledgment).acknowledge();
    }

    @Test
    void 重复消息幂等跳过后也ack() {
        when(depositCreditService.credit(PAYLOAD))
                .thenReturn(new DepositCreditService.CreditResult("DP1", null, true));

        consumer.onDeposit(record(), acknowledgment);

        verify(acknowledgment).acknowledge();
    }

    @Test
    void 处理失败不ack等待重投且不向外抛异常() {
        when(depositCreditService.credit(PAYLOAD))
                .thenThrow(new IllegalStateException("ledger down"));

        assertThatCode(() -> consumer.onDeposit(record(), acknowledgment))
                .doesNotThrowAnyException();

        // 不提交 offset，重启/rebalance 后会重投
        verify(acknowledgment, never()).acknowledge();
    }

    private ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>("deposit-outbox", 0, 0L, "DP1", PAYLOAD);
    }
}