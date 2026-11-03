package com.ray.web3.service;

import com.ray.web3.model.entity.WithdrawRecord;

/**
 * 提币广播后状态追踪服务（第 3 周 Day 3-4：广播与状态追踪）
 * <p>
 * 交易广播成功即置 {@code BROADCAST}，但此刻只是进了内存池（Pending），并未确定上链结果。
 * 本服务负责把 {@code BROADCAST} 记录轮询 {@code eth_getTransactionReceipt} 推进到终态：
 * <ul>
 *   <li>收据未出现（仍在 Pending / 未被打包）——保持 {@code BROADCAST}，下一轮再查；</li>
 *   <li>收据存在且 {@code status=0x1} 且确认数达标——转 {@code SUCCESS}；</li>
 *   <li>收据存在但 {@code status=0x0}（执行 Revert）——转 {@code FAILED} 并记录 revertReason。</li>
 * </ul>
 * 与充值侧"确认阈值"同口径：只有确认数达到 {@code web3.withdraw.track-confirmations} 才判成功，
 * 防区块重组把尚未足够确定的交易误判为终态。替换/加速（{@code REPLACED}）属 Day 5-7，不在本服务职责内
 */
public interface WithdrawTrackingService {

    /**
     * 扫描一批 {@code BROADCAST} 记录并逐个查收据推进终态（由定时扫描器周期调用）。
     * <p>
     * 单轮内部对每条记录独立容错：某条 RPC 失败只留待下轮，不影响其余记录，也不抛出中断整轮
     *
     * @return 本轮实际发生状态跃迁（转 SUCCESS 或 FAILED）的记录数，供调度侧观测与日志
     */
    int trackPendingBroadcasts();

    /**
     * 追踪单笔 {@code BROADCAST} 记录（供查询接口按需即时刷新，或单测直接验证状态机跃迁）。
     * <p>
     * 尚未上块 / 确认不足时不改状态，返回的仍是 {@code BROADCAST} 记录
     *
     * @param withdrawNo 提币单号
     * @return 刷新后的最新记录；单号不存在返回 {@code null}
     */
    WithdrawRecord trackOne(String withdrawNo);

    /**
     * 卡单成因诊断（<b>只读</b>）：收据拿不到时区分“还在排队 / 已被丢弃 / 已被同 Nonce 替换”，
     * 三者的后续处置完全不同（等 / 重播 / 改跟替换交易）。
     * <p>
     * 刻意不写库不改状态：本方法只回答“现在链上是什么情况”，把“怎么处置”留给
     * Day 5-7 的自动加速与第 8 周的风控人工环节；也可单独用于排查与监控取数
     *
     * @param withdrawNo 提币单号
     * @return 诊断结果；非 {@code BROADCAST} 状态（含单号不存在）返回 {@code null}，表示无需诊断
     */
    StuckState diagnoseStuck(String withdrawNo);

    /**
     * 卡单成因： {@code BROADCAST} 未满终态时链上实况的分类结论
     */
    enum StuckState {
        /** 交易仍在内存池排队（查得到交易但无 blockHash）：需提价替换（RBF）才能推进 */
        QUEUED,
        /** 交易已从内存池消失且 nonce 未被消耗：可直接重播原签名交易，无双花风险 */
        DROPPED,
        /** 同 nonce 已被另一笔交易消耗：本笔被覆盖作废，应转 REPLACED 并改追替换交易 */
        REPLACED,
        /** 已中块且执行成功，只是确认数未达标：属正常等待，不是卡单 */
        AWAITING_CONFIRMATION,
        /** 节点不可达/返回错误，无法判定：不做任何猜测，下轮重来 */
        NODE_UNAVAILABLE
    }
}
