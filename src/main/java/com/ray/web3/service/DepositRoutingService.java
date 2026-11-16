package com.ray.web3.service;

import com.ray.web3.consts.DepositConsts;
import com.ray.web3.model.dto.TransferEventDTO;

import java.util.List;

/**
 * 充值业务路由（REQ-BIZ-04 模块四 · 业务闭环 · 第 3 周 Outbox 改造）
 * <p>
 * 把"链上解析出的 Transfer 事件"变成"平台充值记录"，三步一体：
 * 热钱包匹配（{@link DepositWalletService}）→ 幂等落库（唯一键 txHash + logIndex，状态待确认）
 * → 同事务写 {@code outbox_message}，由独立投递器异步发送下游账务。
 * <p>
 * {@code deposit_record} 与 {@code outbox_message} 在同一个本地事务里提交：业务成功消息必在，
 * 业务回滚消息必不在。投递是"至少一次"语义，重复由消费端 {@code consumer_biz} 幂等表挡住。
 * <p>
 * 实时链路（模块三）与历史补拉链路（模块二）共用本路由：两条链路可能先后投递同一笔事件，
 * 幂等由数据库唯一键消化，不会产生重复入账。
 */
public interface DepositRoutingService {

    /**
     * 路由一批 Transfer 事件：命中平台充值地址的写充值记录并发布下游通知
     *
     * @param events 解码后的 Transfer 事件（可为空，直接返回 0）
     * @param source 事件来源，取 {@link DepositConsts.Source}
     * @return 本次新增的充值记录条数（重复事件不计入，也不发布消息）
     */
    long route(List<TransferEventDTO> events, String source);
}
