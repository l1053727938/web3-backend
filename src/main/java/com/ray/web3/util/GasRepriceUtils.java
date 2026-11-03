package com.ray.web3.util;

import java.math.BigInteger;

/**
 * 替换交易（Replace-by-fee）的 gas 单价重算工具（第 3 周 Day 5-7：卡单加速）
 * <p>
 * 节点收替换交易有硬门槛：同 Nonce 的新交易必须比内存池里那笔贵出一定比例，否则直接被
 * {@code replacement transaction underpriced} 拒接（geth 默认 10%、OpenEthereum 12.5%）。
 * 本类把"提价多少、取哪个价、是否超封顶"这三步纯计算收拢到一处，让服务层只负责决策与落库。
 * <p>
 * 刻意做成无状态静态工具（对齐 {@link ChainQueryUtils}/{@link TransactionSigner}）：纯算术不涉及 RPC，
 * 便于逐分支单测，也便于第 8 周把这套价格策略平移到 EIP-1559 交易类型而调用方不改
 */
public final class GasRepriceUtils {

    /** 千分比基数：与 {@code gas-price-bps} 等配置同口径，避免浮点比较歧义 */
    private static final BigInteger BPS_BASE = BigInteger.valueOf(1000L);

    private GasRepriceUtils() {
    }

    /**
     * 按千分比提价：{@code 原价 × (1000 + bumpBps) / 1000}，<b>向上取整</b>且结果必然严格大于原价
     * <p>
     * 向上取整是必须的：向下取整在原价很低时可能算出与原价相同的价（1 gwei × 1.125 取整仍可能等于原价），
     * 等价即不满足节点的"必须更贵"要求，会白花一次广播；bumpBps ≤ 0 视为不提价，也兜底 +1 wei 保证可比性
     *
     * @param original 上一次广播的 gas 单价（wei），非空且非负
     * @param bumpBps  提价千分比，125 = 加价 12.5%；负数按 0 处理
     * @return 提价后的单价（wei）
     * @throws IllegalArgumentException 原价为空或为负
     */
    public static BigInteger bump(BigInteger original, long bumpBps) {
        if (original == null || original.signum() < 0) {
            throw new IllegalArgumentException("原 gas 单价非法（需为非负 wei 值）: " + original);
        }
        long factor = BPS_BASE.longValue() + Math.max(bumpBps, 0L);
        BigInteger scaled = original.multiply(BigInteger.valueOf(factor))
                .add(BPS_BASE.subtract(BigInteger.ONE))   // 向上取整：先加 (基数-1) 再整除
                .divide(BPS_BASE);
        return scaled.compareTo(original) > 0 ? scaled : original.add(BigInteger.ONE);
    }

    /**
     * 取本次替换的候选单价：{@code max(提价值, 链上当前价)}——两个价各挡一类风险：
     * <ul>
     *   <li>只按原价提价：链上价已整体涨过一轮时，提 12.5% 仍低于水线，继续排在队尾白等；</li>
     *   <li>只跟链上价：水线回落时可能比原价还低，节点直接拒接（还可能低于原交易，反把自己覆盖了）。</li>
     * </ul>
     *
     * @param bumped     {@link #bump} 的产物，不可为空
     * @param chainPrice 链上实时价（{@code eth_gasPrice} 加安全垫后的值）；为空视为取价失败，只用提价
     * @return 候选单价（wei）
     */
    public static BigInteger candidate(BigInteger bumped, BigInteger chainPrice) {
        if (bumped == null) {
            throw new IllegalArgumentException("提价后的 gas 单价不能为空");
        }
        if (chainPrice == null || chainPrice.signum() < 0) {
            return bumped;
        }
        return bumped.max(chainPrice);
    }

    /**
     * 是否触及单价封顶：候选价超过 {@code cap} 即应放弃自动加速（宁可不推，也不冒单笔天价 gas 费的风险）
     *
     * @param cap 封顶单价（wei）；为空或 ≤0 表示不设限
     * @return true = 已超封顶，调用方应停止自动加速并转人工
     */
    public static boolean exceedsCap(BigInteger price, BigInteger cap) {
        if (cap == null || cap.signum() <= 0) {
            return false;
        }
        return price != null && price.compareTo(cap) > 0;
    }
}
