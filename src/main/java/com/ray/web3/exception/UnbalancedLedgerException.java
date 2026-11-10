package com.ray.web3.exception;

/**
 * 借贷不平（第 2 周 U1-b）
 * <p>
 * 只有一种处理方式：<b>不落库、不自动修正、抛出去</b>。任何“帮它调平”的补偿逻辑都会把
 * 编程错误伪装成正常业务，直接造成资损且无法追溯。调用方应把它当致命错误上抛，
 * 同时靠 {@code ledger.unbalanced} 指标做资损告警。
 */
public class UnbalancedLedgerException extends RuntimeException {

    public UnbalancedLedgerException(String message) {
        super(message);
    }
}
