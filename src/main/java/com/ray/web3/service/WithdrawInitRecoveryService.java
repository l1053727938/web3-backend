package com.ray.web3.service;

/**
 * INIT 提币接管（第 5 周 Day 5-6）
 * <p>
 * 广播结果不确定的提币停在 INIT；本服务按“扫链真相”做三态判定：
 * 已上链成功 → SUCCESS；已上链失败或被顶替 → FAILED（随后由解冻扫描器退款）；
 * 其余保持 INIT。永不回拨 nonce。
 */
public interface WithdrawInitRecoveryService {

    /**
     * 处理一批 INIT 记录
     *
     * @return 本轮发生状态跃迁的条数
     */
    int recoverOnce();
}
