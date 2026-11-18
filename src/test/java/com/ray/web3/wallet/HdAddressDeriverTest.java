package com.ray.web3.wallet;

import com.ray.web3.config.UserProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HD 地址派生单测（第 3 周）：不连链、不连库
 * <p>
 * 用 Hardhat/Anvil 公开助记词校验派生路径：index 1 必须等于其默认第二个账号，
 * 该结果是 BIP39+BIP44 实现的公开标准答案，可防止路径/偏移写错。
 */
class HdAddressDeriverTest {

    private static final String ANVIL_MNEMONIC =
            "test test test test test test test test test test test junk";

    @Test
    void 按BIP44路径应派生Anvil已知账号() {
        HdAddressDeriver deriver = new HdAddressDeriver(properties(0));

        // index 1 = m/44'/60'/0'/0/1，Anvil/Hardhat 默认第二个账号
        assertThat(deriver.address(1L))
                .isEqualTo("0x70997970c51812dc3a010c7d01b50e0d17dc79c8");
    }

    @Test
    void 默认偏移应生效且同一用户地址稳定() {
        HdAddressDeriver deriver = new HdAddressDeriver(properties(1000));

        assertThat(deriver.index(1L)).isEqualTo(1001);
        String address = deriver.address(1L);
        assertThat(address).matches("^0x[0-9a-f]{40}$");
        assertThat(deriver.address(1L)).isEqualTo(address);
        assertThat(deriver.address(2L)).isNotEqualTo(address);
    }

    @Test
    void 非法userId应报错() {
        HdAddressDeriver deriver = new HdAddressDeriver(properties(1000));

        assertThatThrownBy(() -> deriver.index(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
        assertThatThrownBy(() -> deriver.address(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 非法助记词应在启动时快速失败() {
        UserProperties properties = properties(1000);
        properties.setHdMnemonic("not a valid bip39 mnemonic");

        assertThatThrownBy(() -> new HdAddressDeriver(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BIP39");
    }

    private UserProperties properties(int offset) {
        UserProperties properties = new UserProperties();
        properties.setHdMnemonic(ANVIL_MNEMONIC);
        properties.setHdPassphrase("");
        properties.setDerivationOffset(offset);
        return properties;
    }
}