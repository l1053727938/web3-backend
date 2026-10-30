package com.ray.web3.service.impl;

import com.ray.web3.config.DepositProperties;
import com.ray.web3.config.InfuraProperties;
import com.ray.web3.mapper.DepositWalletMapper;
import com.ray.web3.model.DepositConsts;
import com.ray.web3.model.entity.DepositWallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 模块四热钱包/充值地址匹配离线单测（REQ-BIZ-04）：大小写归一、配置与 DB 并集、DB 优先、脏数据剔除
 */
@ExtendWith(MockitoExtension.class)
class DepositWalletServiceImplTest {

    private static final long CHAIN_ID = 1L;
    private static final String MIXED_CASE_HOT = "0x0fD04a68d3c3A692d6Fa30384D1A87Ef93554eE6";
    private static final String LOWER_HOT = "0x0fd04a68d3c3a692d6fa30384d1a87ef93554ee6";
    private static final String USER_ADDRESS = "0x95222290dd7278aa3ddd389b1e2d9d2f0f4b0001";

    @Mock
    private DepositWalletMapper depositWalletMapper;

    private DepositProperties depositProperties;
    private DepositWalletServiceImpl service;

    @BeforeEach
    void setUp() {
        depositProperties = new DepositProperties();
        InfuraProperties infuraProperties = new InfuraProperties();
        infuraProperties.setChainId(CHAIN_ID);
        service = new DepositWalletServiceImpl(depositWalletMapper, infuraProperties, depositProperties);
    }

    @Test
    void 配置中心热钱包按小写归一后可被命中() {
        depositProperties.setHotWallets(new LinkedHashMap<>(Map.of(MIXED_CASE_HOT, "USDT")));
        when(depositWalletMapper.selectActiveByChain(anyLong())).thenReturn(List.of());

        // 上游 EIP-55 校验和地址大小写混合，匹配必须不受前缀与字母大小写影响
        DepositWallet matched = service.match(MIXED_CASE_HOT.toUpperCase(Locale.ROOT));

        assertThat(matched).isNotNull();
        assertThat(matched.getAddress()).isEqualTo(LOWER_HOT);
        assertThat(matched.getWalletType()).isEqualTo(DepositConsts.WalletType.HOT);
        assertThat(matched.getCurrency()).isEqualTo("USDT");
    }

    @Test
    void 同地址以DB为准且非法地址被剔除() {
        depositProperties.setHotWallets(new LinkedHashMap<>(Map.of(LOWER_HOT, "CONFIG")));
        DepositWallet fromDb = wallet(LOWER_HOT, DepositConsts.WalletType.USER, "u-100");
        fromDb.setCurrency("USDT");
        DepositWallet bad = wallet("not-an-address", DepositConsts.WalletType.HOT, null);
        when(depositWalletMapper.selectActiveByChain(anyLong())).thenReturn(List.of(fromDb, bad));

        DepositWallet matched = service.match(LOWER_HOT);

        // DB 条目覆盖配置中心：库里改成 USER/USDT 必须生效，否则运维在库里的启停会被配置文件兜底复活
        assertThat(matched.getWalletType()).isEqualTo(DepositConsts.WalletType.USER);
        assertThat(matched.getUserId()).isEqualTo("u-100");
        assertThat(service.activeWallets()).hasSize(1);
    }

    @Test
    void 未配置地址返回null不产生充值() {
        depositProperties.setHotWallets(new LinkedHashMap<>());
        when(depositWalletMapper.selectActiveByChain(anyLong())).thenReturn(List.of());

        assertThat(service.match(USER_ADDRESS)).isNull();
    }

    @Test
    void 保存配置时校验地址与用户归属() {
        DepositWallet userWallet = wallet(USER_ADDRESS, DepositConsts.WalletType.USER, null);
        assertThatThrownBy(() -> service.saveWallet(userWallet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");

        DepositWallet badType = wallet(USER_ADDRESS, "COLD", "u-1");
        assertThatThrownBy(() -> service.saveWallet(badType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("地址类型");

        DepositWallet badAddress = wallet("0x123", DepositConsts.WalletType.HOT, null);
        assertThatThrownBy(() -> service.saveWallet(badAddress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("地址格式非法");
    }

    private DepositWallet wallet(String address, String type, String userId) {
        DepositWallet wallet = new DepositWallet();
        wallet.setChainId(CHAIN_ID);
        wallet.setAddress(address);
        wallet.setWalletType(type);
        wallet.setUserId(userId);
        wallet.setStatus(1);
        return wallet;
    }
}
