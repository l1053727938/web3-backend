package com.ray.web3.service;

/**
 * 充值重组回滚（第 5 周 Day 5-6）
 * <p>
 * 实时链路只用 {@code isRemoved()} 丢弃撤销日志，<b>已入库的行没有回退机制</b>；
 * 本服务补的正是后者：把已确认的充值标记 INVALID，并写一组冲正分录。
 * 冲正后对账会把该笔归为已收敛，不会继续报差异。
 */
public interface DepositReorgService {

    /**
     * 回滚一笔充值：置 INVALID + 冲正账本分录（幂等）
     *
     * @return true=本次发生状态变更
     */
    boolean markReorged(String depositNo);

    /**
     * 扫描最近窗口内已确认的充值，校验链上日志是否仍在
     *
     * @return 本轮回滚条数
     */
    int scanRecentReorgs();
}
