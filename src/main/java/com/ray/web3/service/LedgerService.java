package com.ray.web3.service;

import com.ray.web3.model.entity.LedgerEntry;
import com.ray.web3.model.ledger.LedgerGroup;

/**
 * 复式账本服务（第 2 周 U1-b）
 * <p>
 * 对外只有一个写入口 {@link #post(LedgerGroup)}：校验、落分录、回写余额在一个事务里完成，
 * 任何一步失败整组回滚。余额表 {@code account} 只是本服务写出的物化视图，不允许业务代码绕过账本直接改余额。
 */
public interface LedgerService {

    /**
     * 记一组分录
     * <p>
     * 幂等：{@code (bizType, bizNo)} 已存在时直接返回原分录组，不再动余额。
     * 返回原 entry 而不是 void，是为了让“重复投递”在调用方可见（可据此打点/告警），
     * 同时不改动计划里的五步顺序。
     *
     * @param group 分录组，至少两条、同币种借贷相等
     * @return 本次生效的分录组；幂等命中时是库中已存在的那一条
     * @throws com.ray.web3.exception.UnbalancedLedgerException 借贷不平，未落库
     * @throws com.ray.web3.exception.LedgerPostingException     账户不存在/币种不符/余额不足/乐观锁冲突
     */
    LedgerEntry post(LedgerGroup group);
}
