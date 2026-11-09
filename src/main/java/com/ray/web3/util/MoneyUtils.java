package com.ray.web3.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 金额双轨制换算工具（第 4 周 Day 5-6：链上 raw ↔ 账本 DECIMAL）
 * <p>
 * 全系统金额只允许两种形态，不出现第三种：
 * <ul>
 *   <li><b>链上侧</b>：{@link BigInteger} 最小单位整数（uint256），落库仍是 VARCHAR，天然无损；</li>
 *   <li><b>账本侧</b>：{@link BigDecimal}，精度固定 {@value #SCALE} 位，对齐 {@code DECIMAL(38,18)}。</li>
 * </ul>
 * <b>禁止 {@code double}/{@code float} 参与任何金额计算</b>：二进制浮点无法精确表示 0.1 这类十进制数，
 * 一次累加就能让对账差出几厘，且这类误差不可复现。
 * <p>
 * 换算公式固定为 {@code display = raw / 10^decimals}，{@code decimals} 必须来自 {@code token_metadata}
 * 表而不是每笔实时调合约——同一个合约两次 RPC 拿到不同精度，对账会永远对不平。
 * <p>
 * <b>舍入方向全系统只允许 {@link RoundingMode#DOWN}（平台不吃用户零头，资损方向安全），
 * 改这一行之前先改对账规则。</b>
 * <p>
 * 刻意做成无状态静态工具（对齐 {@link GasRepriceUtils}）：纯算术，不碰 RPC、不碰数据库，便于逐分支单测。
 */
public final class MoneyUtils {

    /** 账本精度：对齐 {@code account.available/frozen} 的 DECIMAL(38,18) */
    public static final int SCALE = 18;

    /** DECIMAL(38,18) 的整数位上限 = 38 - 18 */
    public static final int MAX_INTEGER_DIGITS = 20;

    /** 全系统唯一舍入方向：平台不吃用户零头 */
    public static final RoundingMode ROUNDING_MODE = RoundingMode.DOWN;

    /** DECIMAL(38,18) 能容纳的绝对值上界（不含）：10^20 */
    private static final BigDecimal MAX_ABS = new BigDecimal(BigInteger.TEN.pow(MAX_INTEGER_DIGITS));

    private MoneyUtils() {
    }

    /**
     * 链上原始金额 → 账本展示金额：{@code raw / 10^decimals}，结果 scale 恰好等于 {@code decimals}
     * <p>
     * 只做小数点移位，不做任何舍入——uint256 转 BigDecimal 全程精确，不存在精度损失。
     *
     * @param raw      最小单位整数金额（uint256，非负）
     * @param decimals 代币精度（如 USDT=6、ETH=18）
     * @return 展示金额，scale = {@code decimals}
     * @throws IllegalArgumentException 金额为空/为负，或精度为负
     */
    public static BigDecimal toDisplay(BigInteger raw, int decimals) {
        if (raw == null) {
            throw new IllegalArgumentException("链上金额不能为空");
        }
        if (raw.signum() < 0) {
            throw new IllegalArgumentException("链上金额是 uint256，不应为负: " + raw);
        }
        requireNonNegativeDecimals(decimals);
        return new BigDecimal(raw).movePointLeft(decimals);
    }

    /**
     * 展示金额 → 链上原始金额：{@code display × 10^decimals}，不足一个最小单位的部分按
     * {@link #ROUNDING_MODE} 直接丢弃（不进位）
     * <p>
     * 与 {@link #toDisplay} 严格互逆：对任意非负 raw，{@code toRaw(toDisplay(raw, d), d) == raw}。
     * 注意：本方法会静默截断超出精度的部分，用户输入金额的精度校验应在更外层完成。
     *
     * @param display  展示金额（非负）
     * @param decimals 代币精度
     * @return 最小单位整数金额
     * @throws IllegalArgumentException 金额为空/为负，或精度为负
     */
    public static BigInteger toRaw(BigDecimal display, int decimals) {
        if (display == null) {
            throw new IllegalArgumentException("展示金额不能为空");
        }
        if (display.signum() < 0) {
            throw new IllegalArgumentException("金额不能为负: " + display.toPlainString());
        }
        requireNonNegativeDecimals(decimals);
        return display.movePointRight(decimals)
                .setScale(0, ROUNDING_MODE)
                .toBigIntegerExact();
    }

    /**
     * 账本金额归一化：对齐全系统账本精度 {@value #SCALE} 位，多出的位数按 {@link #ROUNDING_MODE} 截断
     * <p>
     * <b>不抛异常</b>：上游（链上解码、行情、盈亏计算）可能带来超过 18 位小数的中间值，这里统一压到
     * DECIMAL(38,18) 可落库的形态；"这个金额合不合法"是业务校验，归调用方管。
     * 但超出 DECIMAL(38,18) 整数位容量（20 位）的值必须在这里拦下——否则会拖到 INSERT 时才报错，
     * 事务里已写入的其他账单将一起回滚。
     *
     * @param value 任意精度的金额（可为负，盈亏/冲正需要）
     * @return scale 恰为 {@value #SCALE} 的金额
     * @throws IllegalArgumentException 金额为空，或绝对值超出 DECIMAL(38,18) 容量
     */
    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("账本金额不能为空");
        }
        BigDecimal scaled = value.setScale(SCALE, ROUNDING_MODE);
        if (scaled.abs().compareTo(MAX_ABS) >= 0) {
            throw new IllegalArgumentException(
                    "账本金额超出 DECIMAL(38,18) 容量（整数位最多 " + MAX_INTEGER_DIGITS
                            + " 位）: " + value.toPlainString());
        }
        return scaled;
    }

    private static void requireNonNegativeDecimals(int decimals) {
        if (decimals < 0) {
            throw new IllegalArgumentException("代币精度不能为负: " + decimals);
        }
    }
}