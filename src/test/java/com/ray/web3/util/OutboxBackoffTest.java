package com.ray.web3.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 退避计算单测（第 3 周 Day 3-4）：指数增长、封顶、非法入参兜底
 */
class OutboxBackoffTest {

    @Test
    void 第一次重试等待基数() {
        assertThat(OutboxBackoff.delaySeconds(1, 5, 300)).isEqualTo(5);
        assertThat(OutboxBackoff.delaySeconds(0, 5, 300)).isEqualTo(5);
        assertThat(OutboxBackoff.delaySeconds(-1, 5, 300)).isEqualTo(5);
    }

    @Test
    void 之后每次翻倍() {
        assertThat(OutboxBackoff.delaySeconds(2, 5, 300)).isEqualTo(10);
        assertThat(OutboxBackoff.delaySeconds(3, 5, 300)).isEqualTo(20);
        assertThat(OutboxBackoff.delaySeconds(4, 5, 300)).isEqualTo(40);
        assertThat(OutboxBackoff.delaySeconds(5, 5, 300)).isEqualTo(80);
        assertThat(OutboxBackoff.delaySeconds(6, 5, 300)).isEqualTo(160);
    }

    @Test
    void 指数增长封顶不溢出() {
        assertThat(OutboxBackoff.delaySeconds(7, 5, 300)).isEqualTo(300);
        assertThat(OutboxBackoff.delaySeconds(100, 5, 300)).isEqualTo(300);
        assertThat(OutboxBackoff.delaySeconds(100, 5, 1_000_000)).isEqualTo(1_000_000);
    }

    @Test
    void 非法配置兜底为至少1秒() {
        assertThat(OutboxBackoff.delaySeconds(1, 0, 0)).isEqualTo(1);
        assertThat(OutboxBackoff.delaySeconds(3, -10, -1)).isEqualTo(1);
        // max 小于 base 时按 base 处理，避免“上限把等待压成 0”
        assertThat(OutboxBackoff.delaySeconds(3, 10, 5)).isEqualTo(10);
    }
}