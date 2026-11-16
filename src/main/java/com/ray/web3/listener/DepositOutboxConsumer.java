package com.ray.web3.listener;

import com.ray.web3.service.impl.DepositCreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 充值 Outbox Kafka 消费者（第 3 周 Day 5-6）
 * <p>
 * 关闭自动提交（{@code spring.kafka.consumer.enable-auto-commit=false}，
 * {@code spring.kafka.listener.ack-mode=manual_immediate}），处理成功才 {@code ack.acknowledge()}：
 * <ul>
 *   <li>首次消费成功 → 记账 → ack；</li>
 *   <li>consumer_biz 命中主键（重复投递）→ 直接 ack 跳过；</li>
 *   <li>任何失败 → 不 ack，offset 不提交，重启/rebalance 后重投；
 *       账本以 depositNo 幂等，重复处理不会重复加钱。</li>
 * </ul>
 * 仅当 {@code web3.outbox.transport=kafka} 时加载。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "web3.outbox.transport", havingValue = "kafka")
public class DepositOutboxConsumer {

    private final DepositCreditService depositCreditService;

    @KafkaListener(
            topics = "${web3.outbox.kafka.topic:deposit-outbox}",
            groupId = "${spring.kafka.consumer.group-id:web3-outbox}")
    public void onDeposit(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            DepositCreditService.CreditResult result = depositCreditService.credit(record.value());
            ack.acknowledge();
            if (result.duplicate()) {
                log.info("[consumer] 幂等命中，跳过 depositNo={} partition={} offset={}",
                        result.depositNo(), record.partition(), record.offset());
            } else {
                log.info("[consumer] 已 ack depositNo={} entryNo={} partition={} offset={}",
                        result.depositNo(), result.entryNo(), record.partition(), record.offset());
            }
        } catch (Exception e) {
            // 失败不 ack：offset 未提交，消息会在重启/rebalance 后重投
            log.error("[consumer] 充值入账失败，不 ack 等待重投 key={} partition={} offset={}: {}",
                    record.key(), record.partition(), record.offset(), e.getMessage(), e);
        }
    }
}