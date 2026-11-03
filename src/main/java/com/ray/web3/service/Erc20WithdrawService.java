package com.ray.web3.service;

import com.ray.web3.model.entity.WithdrawRecord;

import java.math.BigInteger;

/**
 * ERC-20 提币交易服务（第 3 周：写与发交易，CEX 提币网关雏形）
 * <p>
 * 本周打通执行层四步：构造 calldata → Gas 估算 → Nonce 分配 → 本地签名 + 广播；
 * 广播后的确认追踪属于 Day 3-4，卡单加速与撤销覆盖属于 Day 5-7（本接口 {@code accelerate}/{@code cancel}），
 * 业务编排（风控/审核/账本冻结）在第 8 周
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

    /**
     * 加速一笔已卡单的提币（Day 5-7 Replace-by-fee）：拿本行已占的 Nonce 与原始转账字段，
     * <b>只把 gas 单价提上去重新签名广播</b>，让内存池里那笔便宜交易被自己替掉
     * <p>
     * 之所以能安全重播：接收地址与金额与原交易逐字节一致，同 Nonce 决定了这两笔只能活一笔，
     * 不存在“加速一次 = 多提一次币”。加速失败一律不改库也不回拨 Nonce：
     * 该 Nonce 早已被本行提币占用，回拨等于把它再分配给别人 = 双花
     *
     * @param withdrawNo 待加速的提币单号（必须处于 BROADCAST）
     * @return 回填后的最新记录（status 仍为 BROADCAST，txHash/gasPrice 已换成新交易）
     * @throws IllegalArgumentException 单号不存在
     * @throws IllegalStateException    状态非 BROADCAST / 非本热钱包交易 / 候选价超封顶 / 被节点拒绝 / 广播结果不确定
     */
    WithdrawRecord accelerateWithdraw(String withdrawNo);

    /**
     * 撤销一笔已卡单的提币（Day 5-7）：用同一 Nonce 广播一笔“自己转给自己”的 0 值空交易把原交易顶掉，
     * 原单转 {@code REPLACED} 作废（本笔提币再不可能上链执行）
     * <p>
     * 空交易哈希只写进 {@code error_message}、不覆盖 {@code transaction_hash}，否则追踪会拿它的
     * 成功收据把这笔提币误写成 SUCCESS。撤销不归还 Nonce：该 Nonce 由空交易正常消耗，链上口径本就前进
     * <p>
     * ⚠ 本方法只完成链上动作，<b>不做退款/解冻</b>——那属于账务侧业务编排（第 8 周）
     *
     * @param withdrawNo 待撤销的提币单号（必须处于 BROADCAST）
     * @return 回填后的最新记录（status=REPLACED）
     * @throws IllegalArgumentException 单号不存在
     * @throws IllegalStateException    状态非 BROADCAST / 非本热钱包交易 / 被节点拒绝 / 广播结果不确定
     */
    WithdrawRecord cancelWithdraw(String withdrawNo);
}
