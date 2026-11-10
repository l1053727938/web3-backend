package com.ray.web3.model.ledger;

import com.ray.web3.consts.LedgerConsts;

import java.util.List;

/**
 * 分录组（第 2 周 U1-b）：一笔业务 = 一组分录 = 两表里的一行 ledger_entry + N 行 ledger_posting
 * <p>
 * 幂等键是 {@code (bizType, bizNo)}，落在分录组上而不是明细上：
 * 同一笔业务重复投递时，撞唯一键就能整组跳过，不会出现“一半已入账”的中间态。
 * <p>
 * 作为值对象不可变；{@code postings} 在构造时做防御性拷贝。
 *
 * @param bizType  业务类型，见 {@link LedgerConsts.BizType}
 * @param bizNo    业务单号（充值单号/划转单号/提币单号），与 bizType 联合唯一
 * @param postings 分录明细，至少两条（借贷必相等）
 */
public record LedgerGroup(String bizType, String bizNo, List<Posting> postings) {

    public LedgerGroup {
        if (bizType == null || bizType.isBlank()) {
            throw new IllegalArgumentException("bizType 不能为空");
        }
        if (bizType.length() > 32) {
            throw new IllegalArgumentException("bizType 超过 32 字符: " + bizType);
        }
        if (bizNo == null || bizNo.isBlank()) {
            throw new IllegalArgumentException("bizNo 不能为空");
        }
        if (bizNo.length() > 128) {
            throw new IllegalArgumentException("bizNo 超过 128 字符: " + bizNo);
        }
        if (postings == null || postings.size() < 2) {
            throw new IllegalArgumentException("一组分录至少两条 posting（有借必有贷）");
        }
        postings = List.copyOf(postings);
    }
}
