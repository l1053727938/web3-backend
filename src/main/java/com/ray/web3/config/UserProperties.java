package com.ray.web3.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户业务配置（第 3 周：注册 + 充值地址分配）
 * <p>
 * 充值地址用 HD 钱包按用户序号派生：数据库只存地址与派生序号，<b>不存私钥</b>。
 * 主助记词是整个平台用户地址的总钥匙，生产必须通过环境变量/KMS 注入，不能写进仓库。
 */
@Data
@ConfigurationProperties(prefix = "web3.user")
public class UserProperties {

    /**
     * BIP39 主助记词（12/24 个单词）。
     * <p>
     * 默认值仅供本地联调（Anvil/Hardhat 公开助记词，任何人都知道对应私钥）；
     * <b>生产必须用 {@code WEB3_USER_HD_MNEMONIC} 覆盖</b>，否则用户充值地址的私钥等于公开。
     */
    private String hdMnemonic;

    /** BIP39 passphrase（可选，留空表示无） */
    private String hdPassphrase = "";

    /**
     * 派生序号偏移：实际 index = userId + offset。
     * <p>
     * 默认 1000，避开本地 Anvil 默认账号（index 0-9 是公开测试账户）；
     * 同时保证不同用户 id 不会映射到同一个地址。
     */
    private int derivationOffset = 1000;
}
