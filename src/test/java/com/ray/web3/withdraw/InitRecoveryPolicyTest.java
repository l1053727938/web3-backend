package com.ray.web3.withdraw;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INIT 三态判定单测（第 5 周 Day 5-6）
 * <p>
 * 锁死红线：只有“确定失败”才允许转 FAILED（会触发解冻），
 * 任何“不确定”都必须保持 INIT，宁可不处理也不能误退款。
 */
class InitRecoveryPolicyTest {

    @Test
    void 已上链且执行成功应转SUCCESS() {
        InitRecoveryPolicy.Decision d = InitRecoveryPolicy.decide(
                "0xaa", "0xaa", true, true, 10L, 5L);
        assertThat(d.outcome()).isEqualTo(InitRecoveryPolicy.Outcome.SUCCESS);
    }

    @Test
    void 已上链但执行失败应转FAILED() {
        InitRecoveryPolicy.Decision d = InitRecoveryPolicy.decide(
                "0xaa", "0xaa", true, false, 10L, 5L);
        assertThat(d.outcome()).isEqualTo(InitRecoveryPolicy.Outcome.FAILED);
        assertThat(d.reason()).contains("receipt.status=0x0");
    }

    @Test
    void 本地hash被同nonce另一笔顶替应转FAILED() {
        InitRecoveryPolicy.Decision d = InitRecoveryPolicy.decide(
                "0xaa", "0xbb", false, false, 10L, 5L);
        assertThat(d.outcome()).isEqualTo(InitRecoveryPolicy.Outcome.FAILED);
        assertThat(d.reason()).contains("顶替").contains("0xbb");
    }

    @Test
    void 本地无hash但nonce已被占用应保持INIT() {
        // 可能是本笔广播成功但没回填 hash；判失败会重复退款
        InitRecoveryPolicy.Decision d = InitRecoveryPolicy.decide(
                null, "0xbb", false, false, 10L, 5L);
        assertThat(d.outcome()).isEqualTo(InitRecoveryPolicy.Outcome.PENDING);
        assertThat(d.reason()).contains("无法确认归属");
    }

    @Test
    void nonce已消耗但找不到交易应保持INIT() {
        InitRecoveryPolicy.Decision d = InitRecoveryPolicy.decide(
                "0xaa", null, false, false, 10L, 5L);
        assertThat(d.outcome()).isEqualTo(InitRecoveryPolicy.Outcome.PENDING);
        assertThat(d.reason()).contains("nonce 已消耗");
    }

    @Test
    void 池内未见且nonce仍空闲应保持INIT() {
        InitRecoveryPolicy.Decision d = InitRecoveryPolicy.decide(
                null, null, false, false, 5L, 5L);
        assertThat(d.outcome()).isEqualTo(InitRecoveryPolicy.Outcome.PENDING);
        assertThat(d.reason()).contains("内存池未见");
    }
}
