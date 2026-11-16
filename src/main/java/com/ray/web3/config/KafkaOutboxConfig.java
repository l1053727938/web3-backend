package com.ray.web3.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 通道装配（第 3 周 Day 5-6）
 * <p>
 * 仅在 {@code web3.outbox.transport=kafka} 时注册 topic；Spring Boot 的 KafkaAdmin
 * 会在启动时按 {@link NewTopic} 创建 topic（broker 也开了 auto-create 作为兜底）。
 * <p>
 * 默认 1 分区 1 副本：本地 KRaft 单节点；生产按吞吐/顺序要求调整，副本数不得超过 broker 数。
 */
@Configuration
@ConditionalOnProperty(name = "web3.outbox.transport", havingValue = "kafka")
public class KafkaOutboxConfig {

    @Bean
    public NewTopic outboxTopic(OutboxProperties outboxProperties) {
        OutboxProperties.Kafka kafka = outboxProperties.getKafka();
        return TopicBuilder.name(kafka.getTopic())
                .partitions(kafka.getPartitions())
                .replicas(kafka.getReplicas())
                .build();
    }
}