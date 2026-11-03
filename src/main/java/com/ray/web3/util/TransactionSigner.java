package com.ray.web3.util;

import com.ray.web3.consts.PatternConsts;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.List;

/**
 * 离线交易签名工具（第 3 周：写与发交易）
 * <p>
 * 安全红线——私钥绝对不能传到节点：本类只在本地进程内用私钥把 {@link RawTransaction} 签成 RLP 编码的
 * 十六进制裸交易，产出的 hex 才交给 eth_sendRawTransaction 广播；私钥入参、签名出参，全程不出网络。
 * <p>
 * 刻意做成无状态静态工具（对齐 {@link ChainQueryUtils}）：签名是纯计算，不依赖 Spring/RPC，
 * 便于单测逐字节验证，也便于第 8 周把"取私钥"这一步替换为 KMS/HSM 而签名算法本身不动
 */
public final class TransactionSigner {

    private TransactionSigner() {
    }

    /**
     * 私钥 → 凭证：校验为 0x+64 位 hex 裸私钥后本地构造 {@link Credentials}
     *
     * @throws IllegalArgumentException 私钥格式非法（调用方应在读配置阶段先拦，别把非法私钥带进签名环节）
     */
    public static Credentials credentialsOf(String privateKeyHex) {
        if (!PatternConsts.isPrivateKey(privateKeyHex)) {
            throw new IllegalArgumentException("私钥格式非法：需为 0x 前缀 + 64 位十六进制");
        }
        return Credentials.create(privateKeyHex);
    }

    /** 由私钥推导链上发送方地址（小写 0x，供 Nonce 归属与落库 from_address 用） */
    public static String addressOf(Credentials credentials) {
        return credentials.getAddress().toLowerCase();
    }

    /**
     * 构造并 ABI 编码一笔 ERC-20 {@code transfer(address to, uint256 value)} 调用数据
     * <p>
     * value 恒为 0（代币转账走合约调用而非原生 ETH 转账），金额编码进 data；
     * to 是代币合约地址，接收方作为函数入参
     *
     * @param tokenContract ERC-20 合约地址
     * @param recipient     收款地址
     * @param rawValue      最小单位金额（uint256）
     * @return 0x 前缀的 calldata
     */
    public static String encodeErc20Transfer(String tokenContract, String recipient, BigInteger rawValue) {
        Function transfer = new Function(
                "transfer",
                List.of(new Address(recipient), new Uint256(rawValue)),
                List.of(TypeReference.create(Bool.class)));
        return FunctionEncoder.encode(transfer);
    }

    /**
     * 组装 ERC-20 提币的未签名交易（legacy 类型，EIP-155 签名在 {@link #sign} 阶段按 chainId 附加）
     *
     * @param nonce      发送方 Nonce（由 NonceManagerService 分配，见并发口径）
     * @param gasPrice   gas 单价（wei）
     * @param gasLimit   gas 上限（估算加安全垫后的值）
     * @param data       {@link #encodeErc20Transfer} 产出的 calldata
     */
    public static RawTransaction buildErc20Transfer(BigInteger nonce, BigInteger gasPrice, BigInteger gasLimit,
                                                    String tokenContract, String data) {
        return RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, tokenContract, BigInteger.ZERO, data);
    }

    /**
     * 组装撤销用的空交易（Day 5-7）：发给自己、{@code value=0}、{@code data} 为空
     * <p>
     * 原理：节点只按 {@code (from, nonce)} 认定交易身份，不看内容——同 Nonce 的这笔空交易一旦进了内存池，
     * 原提币交易就被覆盖作废。它自己会执行成功（原生转账恒耗 21000 gas），但<b>不转移任何资产</b>，
     * 因此可安全用作“让这笔提币彻底不再发生”的手段（资金侧解冻/退款属业务编排，第 8 周）
     *
     * @param self 热钱包自身地址，同时作为 from 与 to
     */
    public static RawTransaction buildEmptyTransfer(BigInteger nonce, BigInteger gasPrice, BigInteger gasLimit,
                                                   String self) {
        return RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, self, BigInteger.ZERO, "");
    }

    /**
     * 本地离线签名：RawTransaction + 私钥 + 链 ID → EIP-155 签名的裸交易 hex（0x 前缀），
     * 输出即可直接喂给 eth_sendRawTransaction
     */
    public static String sign(RawTransaction transaction, long chainId, Credentials credentials) {
        byte[] signed = TransactionEncoder.signMessage(transaction, chainId, credentials);
        return Numeric.toHexString(signed);
    }
}
