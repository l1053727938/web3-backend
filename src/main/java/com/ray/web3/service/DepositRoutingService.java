package com.ray.web3.service;

import com.ray.web3.model.dto.TransferEventDTO;

import java.util.List;

/**
 * 充值业务路由（REQ-BIZ-04 模块四 · 业务闭环）
 * <p>
 * 把"链上解析出的 Transfer 事件"变成"平台充值记录"，三步一体：
 * 热钱包匹配（{@link DepositWalletService}）→ 幂等落库（唯一键 txHash + logIndex，状态待确认）
 * → Redis 发布 {@code deposit_channel} 通知下游账务。
 * <p>
 * 实时链路（模块三）与历史补拉链路（模块二）共用本路由：两条链路可能先后投递同一笔事件，
 * 幂等由数据库唯一键消化，只有"真正新增"的那次才会发布下游消息，不会重复记账
 */
public interface DepositRoutingService {

    /**
     * 路由一批 Transfer 事件：命中平台充值地址的写充值记录并发布下游通知
     *
     * @param events 解码后的 Transfer 事件（可为空，直接返回 0）
     * @param source 事件来源，取 {@link com.ray.web3.model.DepositConsts.Source}
     * @return 本次新增的充值记录条数（重复事件不计入，也不发布消息）
     */
    long route(List<TransferEventDTO> events, String source);
}
