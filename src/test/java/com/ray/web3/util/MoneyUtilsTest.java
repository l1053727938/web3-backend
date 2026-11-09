package com.ray.web3.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 金额双轨制换算单测（第 4 周 Day 5-6）：纯算术零外部依赖
 * <p>
 * 重点锁死四件事——raw→display 精度不丢、1 wei 能表示、双向可逆、超精度按 DOWN 截断而不是悄悄进位
 */
class MoneyUtilsTest {

    @Test
    void USDT六位精度下一百万最小单位应换算成1点000000() {
        BigDecimal display = MoneyUtils.toDisplay(new BigInteger("1000000"), 6);
        // equals 同时比较 scale：USDT 展示必须带满 6 位小数
        assertThat(display).isEqualTo(new BigDecimal("1.000000"));
        assertThat(display.scale()).isEqualTo(6);
    }

    @Test
    void ETH十八位精度下一wei应换算成最小可表示单位() {
        BigDecimal display = MoneyUtils.toDisplay(BigInteger.ONE, 18);
        assertThat(display).isEqualTo(new BigDecimal("0.000000000000000001"));
        assertThat(display.scale()).isEqualTo(18);
        // 零值也要带满精度，不能退化成 "0"
        assertThat(MoneyUtils.toDisplay(BigInteger.ZERO, 18).scale()).isEqualTo(18);
    }

    @Test
    void toRaw与toDisplay应双向可逆() {
        // USDT：6 位
        BigInteger usdtRaw = new BigInteger("1234567890");
        assertThat(MoneyUtils.toRaw(MoneyUtils.toDisplay(usdtRaw, 6), 6)).isEqualTo(usdtRaw);
        // ETH：18 位，取 uint256 量级验证大数不丢精度
        BigInteger ethRaw = new BigInteger("123456789012345678901234567890");
        assertThat(MoneyUtils.toRaw(MoneyUtils.toDisplay(ethRaw, 18), 18)).isEqualTo(ethRaw);
        // 零值往返（BigDecimal 的 0 带 scale，容易在 equals/toString 上翻车）
        assertThat(MoneyUtils.toRaw(MoneyUtils.toDisplay(BigInteger.ZERO, 18), 18)).isEqualTo(BigInteger.ZERO);
    }

    @Test
    void 超出账本精度的金额应按DOWN截断且非法值应拒绝() {
        // 第 19 位是 1：结果压到 scale=18 且不抛异常
        BigDecimal truncated = MoneyUtils.normalize(new BigDecimal("1.2345678901234567891"));
        assertThat(truncated.scale()).isEqualTo(18);
        assertThat(truncated).isEqualTo(new BigDecimal("1.234567890123456789"));

        // 第 19 位是 9：DOWN 得 ...789，若误用 HALF_UP 会变成 ...790——方向错了就是平台多扣用户钱
        assertThat(MoneyUtils.normalize(new BigDecimal("1.2345678901234567899")))
                .isEqualTo(new BigDecimal("1.234567890123456789"));

        // toRaw 同口径：不足一个最小单位的零头直接丢掉，不能进位
        assertThat(MoneyUtils.toRaw(new BigDecimal("1.0000019"), 6))
                .isEqualTo(new BigInteger("1000001"));

        // 非法输入不静默兜底
        assertThatThrownBy(() -> MoneyUtils.toDisplay(null, 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyUtils.toDisplay(BigInteger.ONE.negate(), 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyUtils.toDisplay(BigInteger.ONE, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyUtils.toRaw(null, 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyUtils.toRaw(new BigDecimal("-1"), 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyUtils.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);

        // DECIMAL(38,18) 整数位只有 20 位：1e20 会溢出，必须在入账前拦下而不是等数据库报错
        assertThatThrownBy(() -> MoneyUtils.normalize(new BigDecimal("100000000000000000000")))
                .isInstanceOf(IllegalArgumentException.class);
        // 上界内（20 个 9）应放行
        assertThat(MoneyUtils.normalize(new BigDecimal("99999999999999999999")))
                .isEqualByComparingTo("99999999999999999999");
    }
}