package com.ray.web3.service.impl;

import com.ray.web3.ledger.mapper.AccountMapper;
import com.ray.web3.service.LedgerAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账本账户管理实现（第 3 周）
 * <p>
 * 走账本连接池与账本事务管理器：账户表属于账本域，余额回写与开户必须同池，
 * 否则会出现“开户在业务连接、余额回写在账本连接”的跨池一致性问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerAccountServiceImpl implements LedgerAccountService {

    private final AccountMapper accountMapper;

    @Override
    @Transactional(transactionManager = "ledgerTransactionManager", rollbackFor = Exception.class)
    public void ensureAccount(long userId, String accountType, String currency) {
        int created = accountMapper.insertIgnoreAccount(userId, accountType, currency);
        if (created > 0) {
            log.info("[ledger] 已开户 userId={} type={} currency={}", userId, accountType, currency);
        }
    }
}
