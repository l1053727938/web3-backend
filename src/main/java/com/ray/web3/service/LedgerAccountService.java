package com.ray.web3.service;

/**
 * 账本账户管理（第 3 周：用户业务接入）
 * <p>
 * 与 {@link LedgerService} 的分工：账本服务只记分录；本服务只负责“账户行是否存在”。
 * 账户行是余额的容器，必须先有行才能被分录更新，但开户本身不产生任何金额。
 */
public interface LedgerAccountService {

    /**
     * 幂等开户：账户已存在时无副作用。
     * <p>
     * 充值消费者在发现“用户尚无该币种账户”时调用，避免注册与账本跨库/跨连接池的原子性问题。
     */
    void ensureAccount(long userId, String accountType, String currency);
}
