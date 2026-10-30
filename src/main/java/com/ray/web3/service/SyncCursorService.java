package com.ray.web3.service;

/**
 * 补拉游标读写：MySQL block_sync_state 为事实来源，Redis 只做读加速
 * <p>
 * 游标值即该合约已完整处理到的区块高度（lastProcessedBlock），下一轮补拉从 +1 开始；
 * 事实与缓存分离是因为 Redis 无法参与 MySQL 事务，若以 Redis 为事实一次过期/重启就会丢进度
 */
public interface SyncCursorService {

    /**
     * 读取该合约游标：Redis 命中优先，未命中回源 MySQL 并回填缓存；无任何记录返回 null
     *
     * @param contractAddress 合约地址（大小写不敏感，内部统一小写）
     * @return 已处理到的区块高度，或 null
     */
    Long readCursor(String contractAddress);

    /**
     * 回填/刷新 Redis 游标缓存（游标由落库事务写入 MySQL，本方法只维护读加速副本）
     *
     * @param contractAddress    合约地址
     * @param lastProcessedBlock 刚推进到的高度
     */
    void cacheCursor(String contractAddress, long lastProcessedBlock);
}
