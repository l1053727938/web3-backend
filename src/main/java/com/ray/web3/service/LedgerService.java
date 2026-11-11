package com.ray.web3.service;

import com.ray.web3.model.entity.LedgerEntry;
import com.ray.web3.model.ledger.LedgerGroup;

import java.math.BigDecimal;

/**
 * 复式账本服务（第 2 周 U1-b / Day 5-6）
 * <p>
 * 对外写入口：{@link #post(LedgerGroup)} 是通用入口，其余方法是四类业务场景的封装与冲正。
 * 所有入口都遵循同一套规则：借贷平衡校验、按 account_id 升序加锁、同事务落分录、乐观锁回写余额，
 * 任何一步失败整组回滚。
 * <p>
 * 余额表 {@code account} 只是本服务写出的物化视图，不允许业务代码绕过账本直接改余额。
 */
public interface LedgerService {

    /**
     * 记一组分录
     * <p>
     * 幂等：{@code (bizType, bizNo)} 已存在时直接返回原分录组，不再动余额。
     *
     * @param group 分录组，至少两条、同币种借贷相等
     * @return 本次生效的分录组；幂等命中时是库中已存在的那一条
     * @throws com.ray.web3.exception.UnbalancedLedgerException 借贷不平，未落库
     * @throws com.ray.web3.exception.LedgerPostingException     账户不存在/币种不符/余额不足/乐观锁冲突
     */
    LedgerEntry post(LedgerGroup group);

    /**
     * 冲正：按 {@code (bizType, bizNo)} 找到原分录组，写一组方向相反的分录。
     * <p>
     * 原分录永远不修改、不删除；冲正组 {@code biz_type=REVERSE}、
     * {@code biz_no=REV:<原 entry_no>}、{@code reverse_of=原 entry_id}。
     * 重复冲正返回首次冲正组（幂等）。
     * <p>
     * 冲正允许把余额扣成负数：更正既成事实比维持“余额非负”更重要，
     * 负余额由对账与风控单独处理，不能在账本层拒绝。
     */
    LedgerEntry reverse(String bizType, String bizNo);

    /** ① 充值落账：用户 available ↑ / 平台代收负债 ↓，金额 = 链上到账额（确认后调用） */
    LedgerEntry deposit(String depositNo,
                        long userAccountId,
                        long platformLiabilityAccountId,
                        BigDecimal amount,
                        String currency);

    /** ② 下单冻结：用户 available ↓ / 用户 frozen ↑，总额不变 */
    LedgerEntry freeze(String orderNo,
                       long userAccountId,
                       BigDecimal amount,
                       String currency);

    /** ④ 提币失败解冻：用户 frozen ↓ / 用户 available ↑，与②方向相反，按原提币单号幂等 */
    LedgerEntry unfreeze(String withdrawNo,
                         long userAccountId,
                         BigDecimal amount,
                         String currency);

    /**
     * ③ 成交清算：本金与手续费分成两组分录，但在同一个事务里提交。
     * <p>
     * 本金：Taker frozen ↓ / Maker available ↑；手续费：付款方 available ↓ / 平台手续费账户 ↑。
     * 手续费为 0 或 null 时只记本金，返回的 {@code fee} 为 null。
     *
     * @return 本金分录组与手续费分录组
     */
    Settlement settle(String tradeNo,
                      long takerAccountId,
                      long makerAccountId,
                      long feePayerAccountId,
                      long platformFeeAccountId,
                      BigDecimal amount,
                      BigDecimal fee,
                      String currency);

    /** 成交清算结果：本金组 + 手续费组（无手续费时 fee 为 null） */
    record Settlement(LedgerEntry principal, LedgerEntry fee) {
    }
}
