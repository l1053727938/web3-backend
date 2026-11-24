package com.ray.web3.withdraw;

import java.util.Locale;

/**
 * INIT 提币三态判定（第 5 周 Day 5-6）
 * <p>
 * INIT 表示“已落库但广播结果不确定”：可能已上链、可能被同 nonce 顶替、也可能还在队列里。
 * 判定只依据链上真相，<b>不能因为查不到就判失败</b>（失败会触发解冻退款，误判即资损）。
 * <p>
 * 三种结果：
 * <ol>
 *   <li><b>SUCCESS</b>：本笔交易已上链且执行成功；</li>
 *   <li><b>FAILED</b>：本笔交易已上链但执行失败，或同 nonce 已被另一笔交易占用（被顶替）；</li>
 *   <li><b>PENDING</b>：池内未见 / 无法确认归属，保持 INIT 等下一轮。</li>
 * </ol>
 * <b>红线</b>：本类不做 nonce 回拨。回拨只能回拨到“确认无人使用”的位置，
 * 属于人工处置，不能让自动任务顺手做。
 */
public final class InitRecoveryPolicy {

    private InitRecoveryPolicy() {
    }

    public enum Outcome {
        SUCCESS,
        FAILED,
        PENDING
    }

    /**
     * @param outcome 处置结果
     * @param reason  写回 error_message 的原因（SUCCESS 时可为空）
     */
    public record Decision(Outcome outcome, String reason) {
    }

    /**
     * @param recordHash    本地记录的 transaction_hash，可为 null（广播结果不确定时未回填）
     * @param foundHash     链上按 (from, nonce) 扫到的交易 hash，可为 null
     * @param txOnChain     本笔交易是否已在链上（以拿到 receipt 为准）
     * @param txSuccess     receipt.status == 0x1
     * @param pendingNonce  发送方当前 pending nonce
     * @param recordNonce   本笔提币占用的 nonce
     */
    public static Decision decide(String recordHash, String foundHash,
                                  boolean txOnChain, boolean txSuccess,
                                  long pendingNonce, long recordNonce) {
        if (txOnChain) {
            return txSuccess
                    ? new Decision(Outcome.SUCCESS, null)
                    : new Decision(Outcome.FAILED, "交易已上链但执行失败（receipt.status=0x0）");
        }

        // 本地有 hash，链上同 nonce 出现了另一笔 → 被顶替/替换，原交易确定不会执行
        if (hasText(recordHash) && hasText(foundHash)
                && !recordHash.equalsIgnoreCase(foundHash)) {
            return new Decision(Outcome.FAILED,
                    "同 nonce 已被另一笔交易顶替: " + foundHash);
        }

        // 本地没有 hash：链上同 nonce 有交易也无法确认是不是本笔（可能是广播成功但回填失败），
        // 判失败会重复退款，因此只能保持 INIT 等人工/后续确认
        if (!hasText(recordHash) && hasText(foundHash)) {
            return new Decision(Outcome.PENDING,
                    "nonce 已被占用但本地无 txHash，无法确认归属，保持 INIT");
        }

        // nonce 已被消耗但扫不到对应交易：可能刚打包、节点视图滞后，不能立即判失败
        if (pendingNonce > recordNonce) {
            return new Decision(Outcome.PENDING,
                    "nonce 已消耗但未找到对应交易，保持 INIT 等待下一轮");
        }

        return new Decision(Outcome.PENDING, "内存池未见该 nonce，保持 INIT");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
