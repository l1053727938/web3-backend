package com.ray.web3.service;

import com.ray.web3.model.entity.WithdrawRecord;

import java.math.BigInteger;

/**
 * ERC-20 提币交易服务（第 3 周：写与发交易，CEX 提币网关雏形）
 * <p>
 * 本周打通执行层四步：构造 calldata → Gas 估算 → Nonce 分配 → 本地签名 + 广播；
 * 广播后的确认追踪属于 Day 3-4，卡单替换属于 Day 5-7，业务编排（风控/审核/账本冻结）在第 8 周
 */
public interface Erc20WithdrawService {

    /**
     * 发起一笔热钱包 ERC-20 提币（同步执行到"已广播"，返回落库记录供后续追踪）
     *
     * @param tokenContract 代币合约地址（0x 前缀，大小写不限、内部归一小写）
     * @param recipient     提币目标地址
     * @param rawValue      提币金额（代币最小单位，uint256）
     * @return 提币记录（status=BROADCAST 含 txHash；失败路径抛异常且记录已落 FAILED/保留 INIT 锚点）
     * @throws IllegalStateException 校验失败 / 估算 Revert / 广播被确定性拒绝 / 广播结果不确定（超时）
     */
    WithdrawRecord submitWithdraw(String tokenContract, String recipient, BigInteger rawValue);

    /**
     * 按提币单号查询记录（供调用方轮询状态 / 对账）
     *
     * @param withdrawNo 提交时返回的单号（WD 前缀）
     * @return 落库记录（含当前 status：INIT/BROADCAST/FAILED）
     * @throws IllegalArgumentException 单号不存在
     */
    WithdrawRecord queryWithdraw(String withdrawNo);
}
