package com.ray.web3.service.impl;

import com.ray.web3.consts.PatternConsts;
import com.ray.web3.model.entity.WithdrawRecord;
import com.ray.web3.service.Erc20WithdrawService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 Anvil 链提币全链路验证（第 3 周 Day 1-2 骨架的集成冒烟）。
 *
 * <p>默认跳过、不进正常构建；仅当环境变量 ANVIL_WITHDRAW_VERIFY=true 时执行。
 * 前置条件：Anvil 已启动、web.infura.chain-id=31337、MTK 已部署且热钱包（账户 #0）持币、
 * MySQL 已执行 schema.sql、Redis 已启动。操作步骤见对话中的验证指引。
 *
 * <p>本类直连 Service 层，绕开 JWT 与限流拦截器（它们只作用于 HTTP 入口）。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ANVIL_WITHDRAW_VERIFY", matches = "true")
class Erc20WithdrawServiceAnvilTest {

    /** 收款人固定用 Anvil 预置账户 #1（每次启动地址恒定） */
    private static final String RECIPIENT = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Autowired
    private Erc20WithdrawService erc20WithdrawService;

    @Test
    void submitWithdrawBroadcastsToAnvil() {
        String tokenContract = System.getenv("MTK_ADDRESS");
        assertNotNull(tokenContract, "请先设置 MTK_ADDRESS 环境变量（forge create 输出的部署地址）");
        assertTrue(PatternConsts.isAddress(tokenContract), "MTK_ADDRESS 不是合法地址: " + tokenContract);

        // 1 枚 MTK（18 位小数的最小单位口径：1_000_000_000_000_000_000）
        WithdrawRecord record = erc20WithdrawService.submitWithdraw(
                tokenContract, RECIPIENT, BigInteger.valueOf(1_000_000_000_000_000_000L));

        assertEquals("BROADCAST", record.getStatus(), "广播成功应落 BROADCAST 状态");
        assertTrue(PatternConsts.isTxHash(record.getTransactionHash()),
                "应回填交易哈希: " + record.getTransactionHash());
        assertNotNull(record.getNonce(), "应记录已分配的 nonce");
        // 首次提币的账户 #0 链上历史交易数为 0，本地游标种子即 0
        assertEquals(0, record.getNonce().intValue(), "Anvil 重置后首笔交易 nonce 应为 0");
    }
}
