package com.ray.web3.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 链上入参校验工具离线单测：三项校验的接受/拒绝口径与文案（原散落在 EventLogService / HistoryBackfillService 私有方法）
 */
class ChainParamsTest {

    private static final String CONTRACT = "0xdac17f958d2ee523a2206206994597c13d831ec7";
    private static final String TX_HASH = "0x" + "a".repeat(64);

    @Test
    void 合约地址校验只接受小写前缀的40位hex() {
        assertThatCode(() -> ChainParams.requireContract(CONTRACT)).doesNotThrowAnyException();
        // 大写十六进制字母合法（EIP-55 校验和地址），但 0X 前缀会被节点当非法 hex，本地先拦
        assertThatCode(() -> ChainParams.requireContract("0x0fD04a68d3c3A692d6Fa30384D1A87Ef93554eE6"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> ChainParams.requireContract("0Xdac17f958d2ee523a2206206994597c13d831ec7"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("合约地址格式非法");
        assertThatThrownBy(() -> ChainParams.requireContract("0xdac17f"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChainParams.requireContract(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 交易哈希校验要求64位hex() {
        assertThatCode(() -> ChainParams.requireTxHash(TX_HASH)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ChainParams.requireTxHash("0x" + "a".repeat(63)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("交易哈希格式非法");
    }

    @Test
    void 区块区间校验拒绝负数与反向区间() {
        assertThatCode(() -> ChainParams.requireRange(BigInteger.ZERO, BigInteger.ZERO))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChainParams.requireRange(BigInteger.valueOf(18000000), BigInteger.valueOf(18001000)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> ChainParams.requireRange(BigInteger.valueOf(18001000), BigInteger.valueOf(18000000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("区块区间非法: [18001000, 18000000]");
        assertThatThrownBy(() -> ChainParams.requireRange(BigInteger.valueOf(-1), BigInteger.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChainParams.requireRange(BigInteger.ONE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
