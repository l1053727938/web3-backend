package com.ray.web3.service;

/**
 * 提币卡单自动加速服务（第 3 周 Day 5-7：交易替换 Replace-by-fee）
 * <p>
 * Day 3-4 把卡单"发现并分类"，本服务负责按成因做<b>能自动做的那部分</b>处置：
 * <ul>
 *   <li>{@code QUEUED}（还在内存池排队）/ {@code DROPPED}（被节点踢掉）——同 Nonce 提价重播，
 *       转账字段逐字节复现原交易，只换 gas 单价；</li>
 *   <li>{@code AWAITING_CONFIRMATION}（已中块只差确认数）——正常等待，不提价；</li>
 *   <li>{@code REPLACED}（Nonce 已被别的交易消耗）——只标注不作废，见方法注释；</li>
 *   <li>{@code NODE_UNAVAILABLE}——问不到成因，什么都不做，下一轮重来。</li>
 * </ul>
 * 与追踪服务同为"周期扫描 + 条件 UPDATE"模式：单条失败不影响整轮，一切拿不准的情况都留给下一轮
 */
public interface WithdrawAccelerateService {

    /**
     * 扫描一批年龄超过 {@code track-stuck-timeout-ms} 的 {@code BROADCAST} 记录并尝试自愈
     * <p>
     * 三道刹车防"越救越贵"：加速次数上限（达上限标注转人工）、gas 单价封顶（超价直接放弃自动加速）、
     * 单轮批次上限（防打爆 RPC 配额）
     *
     * @return 本轮实际采取动作（重播成功 / 标注转人工）的笔数，供调度侧观测
     */
    int accelerateStuckBroadcasts();
}
