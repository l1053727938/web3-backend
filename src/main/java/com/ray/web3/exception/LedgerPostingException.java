package com.ray.web3.exception;

/**
 * 记账失败（第 2 周 U1-b）：账户不存在、币种不匹配、子账余额不足、乐观锁冲突等
 * <p>
 * 与 {@link UnbalancedLedgerException} 的区别：这类是“可以重试或需要人工确认”的业务失败，
 * 借贷不平则是代码 bug / 数据错误，永远不能重试成成功。
 */
public class LedgerPostingException extends RuntimeException {

    public LedgerPostingException(String message) {
        super(message);
    }
}
