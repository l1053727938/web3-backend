package com.ray.web3.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 替换交易提价算法单测：纯算术零外部依赖
 * <p>
 * 重点锁死三件事——提价必须严格高于原价（等价会被节点拒）、向上取整不多算、封顶判定只看"超过"
 */
class GasRepriceUtilsTest {

    private static final BigInteger GWEI = BigInteger.valueOf(1_000_000_000L);

    @Test
    void 按千分比提价应精确等于乘率结果() {
        // 1000 × 1.125 = 1125，整除场景不多不少
        assertThat(GasRepriceUtils.bump(BigInteger.valueOf(1000L), 125L))
                .isEqualTo(BigInteger.valueOf(1125L));
        // 2 gwei × 1.125 = 2.25 gwei = 2250000000 wei（本档整除，不得多算 1 wei）
        assertThat(GasRepriceUtils.bump(GWEI.multiply(BigInteger.valueOf(2L)), 125L))
                .isEqualTo(new BigInteger("2250000000"));
    }

    @Test
    void 不可整除时应向上取整而非向下() {
        // 888888888 × 1125 = 999999999000，恰除得 999999999（验证加基数减一的技巧没有多算 1）
        assertThat(GasRepriceUtils.bump(BigInteger.valueOf(888_888_888L), 125L))
                .isEqualTo(BigInteger.valueOf(999_999_999L));
        // 1 wei 提 12.5% → 1.125 向上取整为 2（向下取整会等于原价，节点直接拒）
        assertThat(GasRepriceUtils.bump(BigInteger.ONE, 125L)).isEqualTo(BigInteger.TWO);
    }

    @Test
    void 提价结果必须严格大于原价() {
        // 极小 bump：向上取整后仍要大于原价，否则替换交易与原价等价
        assertThat(GasRepriceUtils.bump(GWEI, 1L)).isGreaterThan(GWEI);
        // bump=0 与负数：不提价也要 +1 wei 兜底，保证"更贵"这一硬前提成立
        assertThat(GasRepriceUtils.bump(GWEI, 0L)).isEqualTo(GWEI.add(BigInteger.ONE));
        assertThat(GasRepriceUtils.bump(GWEI, -50L)).isEqualTo(GWEI.add(BigInteger.ONE));
        // 原价为 0（本地链 gasPrice=0 的可能取值）也要得到正价，否则 0 价替换交易永远排不进块
        assertThat(GasRepriceUtils.bump(BigInteger.ZERO, 125L)).isPositive();
    }

    @Test
    void 原价非法时应拒绝而不是猜一个价() {
        assertThatThrownBy(() -> GasRepriceUtils.bump(null, 125L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GasRepriceUtils.bump(GWEI.negate(), 125L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 大数值提价不应溢出() {
        BigInteger huge = new BigInteger("99999999999999999999999999999");
        assertThat(GasRepriceUtils.bump(huge, 125L)).isGreaterThan(huge);
    }

    @Test
    void 候选价应取提价值与链上价的较大者() {
        BigInteger bumped = BigInteger.valueOf(1125L);
        // 链价更高：说明水线涨过一轮，只按原价提 12.5% 会继续排队尾
        assertThat(GasRepriceUtils.candidate(bumped, BigInteger.valueOf(2000L)))
                .isEqualTo(BigInteger.valueOf(2000L));
        // 链价更低：必须用提价值，否则会广播出比原交易更便宜的替换（可能被拒甚至无意义）
        assertThat(GasRepriceUtils.candidate(bumped, BigInteger.valueOf(900L)))
                .isEqualTo(bumped);
    }

    @Test
    void 链上价取不到时应降级只用提价值() {
        BigInteger bumped = BigInteger.valueOf(1125L);
        assertThat(GasRepriceUtils.candidate(bumped, null)).isEqualTo(bumped);
        assertThat(GasRepriceUtils.candidate(bumped, BigInteger.valueOf(-1L))).isEqualTo(bumped);
        assertThatThrownBy(() -> GasRepriceUtils.candidate(null, GWEI))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 封顶判定只在真正超过时成立() {
        BigInteger cap = GWEI.multiply(BigInteger.valueOf(200L));
        assertThat(GasRepriceUtils.exceedsCap(cap.add(BigInteger.ONE), cap)).isTrue();
        // 恰好等于封顶不算超：允许用满预算的那一档价广播
        assertThat(GasRepriceUtils.exceedsCap(cap, cap)).isFalse();
        assertThat(GasRepriceUtils.exceedsCap(GWEI, cap)).isFalse();
    }

    @Test
    void 封顶关闭时任何价格都不算越界() {
        assertThat(GasRepriceUtils.exceedsCap(GWEI.shiftLeft(200), null)).isFalse();
        assertThat(GasRepriceUtils.exceedsCap(GWEI.shiftLeft(200), BigInteger.ZERO)).isFalse();
        assertThat(GasRepriceUtils.exceedsCap(GWEI.shiftLeft(200), BigInteger.valueOf(-1L))).isFalse();
        assertThat(GasRepriceUtils.exceedsCap(null, GWEI)).isFalse();
    }
}
