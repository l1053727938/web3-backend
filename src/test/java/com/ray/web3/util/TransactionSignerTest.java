package com.ray.web3.util;

import com.ray.web3.consts.PatternConsts;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线签名工具单测：纯本地计算零外部依赖。
 * 私钥/地址向量取 Anvil 默认账户 #0（本地链调试的标准测试密钥，无资产，可安全入库）
 */
class TransactionSignerTest {

    /** Anvil 默认账户 #0 私钥（与 application.yml 提币热钱包同源） */
    private static final String ANVIL_PK_0 =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    /** 账户 #0 的标准地址（cast wallet address 可交叉验证），小写归一后作硬断言 */
    private static final String ANVIL_ADDR_0 = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";

    private static final long CHAIN_ID = 31337L;

    @Test
    void credentialsOfDerivesKnownAnvilAddress() {
        Credentials creds = TransactionSigner.credentialsOf(ANVIL_PK_0);
        String address = TransactionSigner.addressOf(creds);
        // 派生地址必须等于 Anvil 预存钱的账户 #0：不一致则本地链验证无法对齐余额/Nonce
        assertEquals(ANVIL_ADDR_0, address);
        assertEquals(address, TransactionSigner.addressOf(TransactionSigner.credentialsOf(ANVIL_PK_0)));
    }

    @Test
    void credentialsOfRejectsMalformedKey() {
        assertThrows(IllegalArgumentException.class,
                () -> TransactionSigner.credentialsOf("short-key"));
        assertThrows(IllegalArgumentException.class,
                () -> TransactionSigner.credentialsOf("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"));
    }

    @Test
    void encodeErc20TransferMatchesAbiLayout() {
        String contract = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
        String data = TransactionSigner.encodeErc20Transfer(
                contract, contract, BigInteger.TEN);
        // transfer(address,uint256) 选择器 = a9059cbb；2 个 32 字节字头参数 → 0x + 8 + 128 hex
        assertTrue(data.startsWith("0xa9059cbb"), "选择器应为 transfer(address,uint256) 的 a9059cbb");
        assertEquals(2 + 8 + 128, data.length());
        // 第二个字头末尾即 uint256 值 10 = 0x0a
        assertTrue(data.endsWith("0a"));
    }

    @Test
    void signIsDeterministicAndProducesPrefixedRawTx() {
        Credentials creds = TransactionSigner.credentialsOf(ANVIL_PK_0);
        String contract = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
        RawTransaction tx = TransactionSigner.buildErc20Transfer(
                BigInteger.ZERO, BigInteger.valueOf(1_000_000_000L), BigInteger.valueOf(60_000L),
                contract, TransactionSigner.encodeErc20Transfer(contract, contract, BigInteger.ONE));

        String first = TransactionSigner.sign(tx, CHAIN_ID, creds);
        String second = TransactionSigner.sign(tx, CHAIN_ID, creds);
        // ECDSA + RFC 6979 确定性签名：同一交易同一密钥两次签名结果必须一致
        assertEquals(first, second);
        assertTrue(first.startsWith("0x"));
    }

    @Test
    void buildEmptyTransferCarriesNoValueAndDiffersFromErc20OnSameNonce() {
        Credentials creds = TransactionSigner.credentialsOf(ANVIL_PK_0);
        BigInteger nonce = BigInteger.valueOf(7L);
        BigInteger gasPrice = BigInteger.valueOf(2_000_000_000L);
        BigInteger gasLimit = BigInteger.valueOf(21_000L);

        RawTransaction empty = TransactionSigner.buildEmptyTransfer(nonce, gasPrice, gasLimit, ANVIL_ADDR_0);
        // 空交易本质：value=0 + data 为空 + 收款人是自己（同 nonce 才能顶掉原交易，不转资产才不会多提一笔）
        assertEquals(BigInteger.ZERO, empty.getValue());
        assertEquals(ANVIL_ADDR_0, empty.getTo());
        assertEquals("", empty.getData());
        assertEquals(nonce, empty.getNonce());

        String emptyHex = TransactionSigner.sign(empty, CHAIN_ID, creds);
        assertEquals(emptyHex, TransactionSigner.sign(
                TransactionSigner.buildEmptyTransfer(nonce, gasPrice, gasLimit, ANVIL_ADDR_0), CHAIN_ID, creds));

        // 同 nonce/同价但带 transfer calldata 的提币交易必须签出不同结果，且长度更大：
        // 替换交易靠的就是“同 nonce + 不同内容”让节点认作两笔
        RawTransaction erc20 = TransactionSigner.buildErc20Transfer(nonce, gasPrice, gasLimit,
                ANVIL_ADDR_0, TransactionSigner.encodeErc20Transfer(ANVIL_ADDR_0, ANVIL_ADDR_0, BigInteger.ONE));
        String erc20Hex = TransactionSigner.sign(erc20, CHAIN_ID, creds);
        assertNotEquals(emptyHex, erc20Hex);
        assertTrue(emptyHex.length() < erc20Hex.length(), "空交易 data 为空，RLP 长度必然小于带 68 字节 calldata 的转账");
    }
}
