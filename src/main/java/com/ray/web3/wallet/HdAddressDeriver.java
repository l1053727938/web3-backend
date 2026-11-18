package com.ray.web3.wallet;

import com.ray.web3.config.UserProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Bip32ECKeyPair;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.MnemonicUtils;

/**
 * 用户充值地址派生器（第 3 周）
 * <p>
 * BIP39 助记词 → BIP32 主密钥 → BIP44 路径 {@code m/44'/60'/0'/0/{index}} 派生地址，
 * 其中 {@code index = userId + web3.user.derivation-offset}。
 * <p>
 * 为什么用 HD 派生而不是随机生成：
 * <ul>
 *   <li>同一用户重复注册/补拉得到同一地址，配合 {@code uk_chain_user} 兜住并发；</li>
 *   <li>数据库只存地址与派生序号，私钥可由主助记词随时重建，不落库；</li>
 *   <li>offset 避开 Anvil/Hardhat 默认测试账号（index 0-9），防止本地公开私钥与用户地址重合。</li>
 * </ul>
 * 主助记词等价于所有用户地址的私钥，生产必须通过环境变量/KMS 注入。
 */
@Slf4j
@Component
public class HdAddressDeriver {

    /** BIP44 purpose：44'，EVM 兼容链通用 */
    private static final int PURPOSE = 44 | Bip32ECKeyPair.HARDENED_BIT;
    /** BIP44 coin type：60'（ETH） */
    private static final int COIN_TYPE = 60 | Bip32ECKeyPair.HARDENED_BIT;
    /** BIP44 account：0' */
    private static final int ACCOUNT = 0 | Bip32ECKeyPair.HARDENED_BIT;
    /** BIP44 change：0，外部地址（用于收款） */
    private static final int CHANGE = 0;

    private final UserProperties userProperties;

    /** BIP32 主密钥：构造时由助记词派生一次，后续派生只读 */
    private final Bip32ECKeyPair masterKey;

    public HdAddressDeriver(UserProperties userProperties) {
        this.userProperties = userProperties;

        String mnemonic = normalizeMnemonic(userProperties.getHdMnemonic());
        if (mnemonic == null) {
            throw new IllegalStateException("web3.user.hd-mnemonic 未配置，无法派生用户充值地址");
        }
        if (!MnemonicUtils.validateMnemonic(mnemonic)) {
            throw new IllegalStateException("web3.user.hd-mnemonic 不是合法的 BIP39 助记词");
        }

        String passphrase = userProperties.getHdPassphrase() == null
                ? "" : userProperties.getHdPassphrase();
        byte[] seed = MnemonicUtils.generateSeed(mnemonic, passphrase);
        this.masterKey = Bip32ECKeyPair.generateKeyPair(seed);

        log.info("[wallet] HD 地址派生器已就绪 derivationOffset={}", userProperties.getDerivationOffset());
    }

    /**
     * 用户对应的 BIP44 派生序号：{@code index = userId + offset}。
     *
     * @throws IllegalArgumentException userId 非正数，或序号超出 BIP32 非硬化范围
     */
    public int index(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId 必须为正数: " + userId);
        }
        long index = userId + userProperties.getDerivationOffset();
        if (index < 0 || index > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("派生序号超出范围: userId=" + userId
                    + " offset=" + userProperties.getDerivationOffset());
        }
        return (int) index;
    }

    /**
     * 派生用户充值地址。
     *
     * @return 0x 前缀的 40 位小写十六进制地址（与 deposit_wallet.address 存储口径一致）
     */
    public String address(long userId) {
        int[] path = {PURPOSE, COIN_TYPE, ACCOUNT, CHANGE, index(userId)};
        Credentials credentials = Credentials.create(Bip32ECKeyPair.deriveKeyPair(masterKey, path));
        return credentials.getAddress();
    }

    /** 统一空白字符，避免配置里多空格导致 BIP39 校验失败 */
    private String normalizeMnemonic(String mnemonic) {
        if (mnemonic == null || mnemonic.trim().isEmpty()) {
            return null;
        }
        return mnemonic.trim().replaceAll("\\s+", " ");
    }
}