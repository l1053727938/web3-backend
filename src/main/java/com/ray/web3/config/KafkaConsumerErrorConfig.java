package com.ray.web3.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka 消费端错误处理（第 4 周 Day 3-4 演练发现的问题）
 * <p>
 * Spring Kafka 默认的 {@code DefaultErrorHandler} 在重试 10 次后会把失败消息**提交 offset 跳过**
 * （没有 recoverer 时按“已恢复”处理）。演练中一条充值消息因瞬态 bug 处理失败，
 * 结果被静默丢弃：outbox 是 SENT、deposit_record 在，但账本没有——这是真正的丢消息。
 * <p>
 * 这里改为：指数退避（1s 起、翻倍、封顶 30s）无限重试，且 {@code ackAfterHandle=false}，
 * 失败消息不提交 offset，重启/rebalance 后继续重投。
 * <p>
 * 代价是毒消息会阻塞所在 partition（队头阻塞），下一步应补消费端 DLQ（重试 N 次后落
 * 死信表并放行），那是第 5 周与对账一起做的部分；当前优先级是“宁可阻塞，不可丢失”。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "web3.outbox.transport", havingValue = "kafka")
public class KafkaConsumerErrorConfig {

    @Bean
    public DefaultErrorHandler kafkaConsumerErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(backOff);
        // 不提交失败消息的 offset：让同分区从头重投，直到处理成功
        handler.setAckAfterHandle(false);
        log.info("[kafka-consumer] 错误处理器已启用：指数退避无限重试（不提交失败 offset）");
        return handler;
    }
}
