package com.ray.web3.service;

import com.ray.web3.model.entity.DepositWallet;

import java.util.List;

/**
 * 充值地址匹配（REQ-BIZ-04 模块四 · 热钱包匹配）
 * <p>
 * 地址来源两处取并集：配置中心 {@code web3.deposit.hot-wallets}（平台热钱包，量少改动少）
 * 与数据库 {@code deposit_wallet}（用户专属充值地址，量大频繁变动）；
 * 全量启用地址在内存按小写地址建索引并按 TTL 回源，实时链路逐事件只做内存查找，不打 DB
 */
public interface DepositWalletService {

    /**
     * 按转入地址匹配平台充值地址
     *
     * @param toAddress 解码出的 to 地址（大小写不敏感）
     * @return 命中的启用地址配置；非平台地址返回 null
     */
    DepositWallet match(String toAddress);

    /** 当前生效的全部充值地址配置（配置中心 + 数据库，调试/验收用） */
    List<DepositWallet> activeWallets();

    /** 强制立即回源刷新缓存（新增/停用地址后无需等 TTL） */
    void invalidateCache();

    /**
     * 新增或更新充值地址配置（按 chainId + 小写地址 upsert），写完立即失效缓存
     *
     * @return 落库后的配置
     */
    DepositWallet saveWallet(DepositWallet wallet);

    /**
     * 停用充值地址（status=0，保留历史充值记录可追溯），写完立即失效缓存
     *
     * @return true=存在并已停用，false=未找到该地址
     */
    boolean disableWallet(Long chainId, String address);
}
