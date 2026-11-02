package com.ray.web3.service;

import java.math.BigInteger;

/**
 * 发交易钱包的 Nonce 管理服务（第 3 周：写与发交易）
 * <p>
 * 解决的核心问题：同一热钱包并发发交易时 Nonce 不冲突。链上口径（eth_getTransactionCount）在
 * "读 Nonce → 签名 → 广播"的窗口期内对并发请求返回同一个值，后广播的一笔会覆盖先广播的一笔；
 * 因此对外发交易的 Nonce 一律从 wallet_nonce 表单点分配，链上值只用于初始种子与校准
 */
public interface NonceManagerService {

    /**
     * 分配下一个 Nonce（分配即推进，行锁串行化）：并发调用各自拿到互不相同的连续值
     *
     * @return 本次分配给调用方的 Nonce
     */
    BigInteger allocateNext(long chainId, String address);

    /**
     * 无锁预览当前待分配 Nonce（观测/排查用，不可直接用于签名发交易）
     */
    BigInteger peek(long chainId, String address);

    /**
     * 与链上对齐校准：next = max(本表游标, 链上 pending)，<b>只前进不回退</b>——
     * 回退可能把已上链的 Nonce 再次分配出去，等同双花。用于两类场景：
     * 游标落后于链（钱包在系统外被发过交易）、周期性防漂移
     */
    BigInteger alignToChain(long chainId, String address);

    /**
     * 广播被节点<b>确定性拒绝</b>（明确错误响应，非超时）后的游标回拨：交易确认没进内存池，
     * 把游标退回链上 pending 复用该 Nonce——不回拨会留下 Nonce 空洞，后续交易全部卡成 queued
     */
    void reallocateAfterReject(long chainId, String address);

    /** 链上 pending 口径的 Nonce（含内存池已排队交易），校准与初始种子用 */
    BigInteger chainPendingNonce(String address);
}
