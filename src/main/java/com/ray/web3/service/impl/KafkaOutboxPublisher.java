package com.ray.web3.service.impl;

import com.ray.web3.config.OutboxProperties;
import com.ray.web3.model.entity.OutboxMessage;
import com.ray.web3.service.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka 版 Outbox 投递通道（第 3 周 Day 5-6）
 * <p>
 * Outbox 投递器调用本类把消息同步发到 Kafka：
 * <ul>
 *   <li>key = {@code bizNo}（充值单号），同一业务键的消息进入同一 partition，便于排查与幂等；</li>
 *   <li>value = outbox_message.payload（入账当时的快照 JSON）；</li>
 *   <li>{@code .get(timeout)} 等待 broker 确认：只有真正写入 Kafka 才返回，
 *       超时/异常抛给投递器 → retry_count+1 → 退避重试；</li>
 *   <li>“发成功但改 outbox 状态前宕机”会重发，消费端 consumer_biz 幂等兜底。</li>
 * </ul>
 * 仅当 {@code web3.outbox.transport=kafka} 时加载，Redis 模式不受影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "web3.outbox.transport", havingValue = "kafka")
public class KafkaOutboxPublisher implements OutboxPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties outboxProperties;

    @Override
    public void publish(OutboxMessage message) {
        String topic = outboxProperties.getKafka().getTopic();
        try {
            kafkaTemplate.send(topic, message.getBizNo(), message.getPayload())
                    .get(outboxProperties.getKafka().getSendTimeoutSeconds(), TimeUnit.SECONDS);
            log.debug("[outbox] Kafka 投递成功 topic={} bizNo={}", topic, message.getBizNo());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka 投递被中断 topic=" + topic
                    + " bizNo=" + message.getBizNo(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka 投递失败 topic=" + topic
                    + " bizNo=" + message.getBizNo(), e);
        }
    }
}