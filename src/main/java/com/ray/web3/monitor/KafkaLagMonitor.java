package com.ray.web3.monitor;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Kafka 消费积压指标（第 4 周 Day 3-4）
 * <p>
 * 自算 {@code lag = endOffset - committedOffset}，按 topic/partition 暴露为 Micrometer Gauge，
 * Actuator + Prometheus 下即 {@code kafka_consumer_lag}。不依赖 Spring Kafka 的指标实现：
 * <ul>
 *   <li>只想看“还剩多少没消费”，AdminClient 两个接口就够；</li>
 *   <li>指标名与 tag 由我们自己定，不会随框架版本漂移；</li>
 *   <li>消费者停掉时拉不到 committed offset 也不影响，保持上一轮值并只打日志。</li>
 * </ul>
 * 仅 {@code web3.outbox.transport=kafka} 时启用；Redis 通道没有 consumer group。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "web3.outbox.transport", havingValue = "kafka")
public class KafkaLagMonitor {

    private final KafkaProperties kafkaProperties;
    private final MeterRegistry meterRegistry;

    /** topic-partition → 最新 lag，Gauge 直接读取，避免每轮重复注册 */
    private final Map<TopicPartition, Long> lagByPartition = new ConcurrentHashMap<>();
    private final Set<TopicPartition> registeredPartitions = ConcurrentHashMap.newKeySet();
    private final AtomicLong totalLag = new AtomicLong();

    private final String groupId;

    private volatile AdminClient adminClient;

    public KafkaLagMonitor(KafkaProperties kafkaProperties, MeterRegistry meterRegistry) {
        this.kafkaProperties = kafkaProperties;
        this.meterRegistry = meterRegistry;
        String configuredGroup = kafkaProperties.getConsumer() == null
                ? null : kafkaProperties.getConsumer().getGroupId();
        this.groupId = (configuredGroup == null || configuredGroup.isBlank())
                ? "web3-outbox" : configuredGroup;
        Gauge.builder("kafka.consumer.lag.total", totalLag, AtomicLong::doubleValue)
                .description("Kafka 消费积压总量（所有分区 lag 之和）")
                .tag("group", this.groupId)
                .register(meterRegistry);
    }

    @PostConstruct
    void init() {
        try {
            Map<String, Object> config = new HashMap<>();
            config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                    String.join(",", kafkaProperties.getBootstrapServers()));
            config.put(AdminClientConfig.CLIENT_ID_CONFIG, "web3-lag-monitor");
            config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
            config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000);
            this.adminClient = AdminClient.create(config);
            log.info("[kafka-lag] 监控已启用 group={} bootstrap={}", groupId,
                    String.join(",", kafkaProperties.getBootstrapServers()));
        } catch (Exception e) {
            log.warn("[kafka-lag] AdminClient 初始化失败，lag 指标暂不可用: {}", e.getMessage());
        }
    }

    @PreDestroy
    void destroy() {
        AdminClient client = adminClient;
        if (client != null) {
            client.close();
        }
    }

    @Scheduled(fixedDelayString = "${web3.outbox.kafka.lag-refresh-ms:15000}",
            initialDelayString = "${web3.outbox.kafka.lag-refresh-ms:15000}")
    public void refresh() {
        AdminClient client = adminClient;
        if (client == null) {
            return;
        }
        try {
            Map<TopicPartition, OffsetAndMetadata> committed =
                    client.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();
            if (committed == null || committed.isEmpty()) {
                totalLag.set(0L);
                return;
            }

            Map<TopicPartition, OffsetSpec> request = committed.keySet().stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            ListOffsetsResult ends = client.listOffsets(request);

            long total = 0L;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
                TopicPartition tp = entry.getKey();
                long current = Math.max(0L, entry.getValue().offset());
                long end;
                try {
                    end = ends.partitionResult(tp).get().offset();
                } catch (Exception e) {
                    log.debug("[kafka-lag] 读取 endOffset 失败 topic={} partition={}: {}",
                            tp.topic(), tp.partition(), e.getMessage());
                    continue;
                }
                long lag = Math.max(0L, end - current);
                lagByPartition.put(tp, lag);
                registerPartitionGauge(tp);
                total += lag;
            }
            totalLag.set(total);
        } catch (Exception e) {
            // broker 不可达/group 刚创建还没有提交位点：保留上一轮数值，避免指标瞬间归零误导告警
            log.debug("[kafka-lag] 刷新失败 group={}: {}", groupId, e.getMessage());
        }
    }

    private void registerPartitionGauge(TopicPartition tp) {
        if (!registeredPartitions.add(tp)) {
            return;
        }
        Gauge.builder("kafka.consumer.lag", lagByPartition,
                        map -> map.getOrDefault(tp, 0L).doubleValue())
                .description("Kafka 消费积压（endOffset - committedOffset）")
                .tag("topic", tp.topic())
                .tag("partition", Integer.toString(tp.partition()))
                .tag("group", groupId)
                .register(meterRegistry);
    }
}
