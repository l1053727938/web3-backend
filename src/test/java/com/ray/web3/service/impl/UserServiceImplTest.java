package com.ray.web3.service.impl;

import com.ray.web3.config.InfuraProperties;
import com.ray.web3.consts.DepositConsts;
import com.ray.web3.mapper.UserMapper;
import com.ray.web3.model.entity.DepositWallet;
import com.ray.web3.model.entity.User;
import com.ray.web3.service.DepositWalletService;
import com.ray.web3.service.UserService;
import com.ray.web3.wallet.HdAddressDeriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户注册离线单测（第 3 周）：全部 Mock，不连 MySQL
 * <p>
 * 重点：注册必须同时落“用户 + 充值地址”；重复注册幂等，不能派生第二个地址。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final long CHAIN_ID = 31337L;

    @Mock
    private UserMapper userMapper;
    @Mock
    private DepositWalletService depositWalletService;
    @Mock
    private HdAddressDeriver addressDeriver;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        InfuraProperties infuraProperties = new InfuraProperties();
        infuraProperties.setChainId(CHAIN_ID);
        service = new UserServiceImpl(userMapper, depositWalletService, addressDeriver, infuraProperties);
    }

    @Test
    void 注册应创建用户并分配USER充值地址() {
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, User.class).setId(1001L);
            return 1;
        });
        when(depositWalletService.findUserWallet(CHAIN_ID, 1001L)).thenReturn(null);
        when(addressDeriver.address(1001L)).thenReturn("0xabc0000000000000000000000000000000000001");
        when(addressDeriver.index(1001L)).thenReturn(2001);
        when(depositWalletService.saveWallet(any(DepositWallet.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, DepositWallet.class));

        UserService.Registered registered = service.register("alice");

        assertThat(registered.userId()).isEqualTo(1001L);
        assertThat(registered.username()).isEqualTo("alice");
        assertThat(registered.depositAddress())
                .isEqualTo("0xabc0000000000000000000000000000000000001");

        ArgumentCaptor<DepositWallet> captor = ArgumentCaptor.forClass(DepositWallet.class);
        verify(depositWalletService).saveWallet(captor.capture());
        DepositWallet wallet = captor.getValue();
        assertThat(wallet.getChainId()).isEqualTo(CHAIN_ID);
        assertThat(wallet.getWalletType()).isEqualTo(DepositConsts.WalletType.USER);
        assertThat(wallet.getUserId()).isEqualTo("1001");
        assertThat(wallet.getDerivationIndex()).isEqualTo(2001);
        assertThat(wallet.getStatus()).isEqualTo(1);
        // 同一地址接收任意代币，币种由 token-symbols 按到账合约反查
        assertThat(wallet.getCurrency()).isNull();
        assertThat(wallet.getContract()).isNull();
    }

    @Test
    void 重复注册应返回原用户原地址且不重复分配() {
        User existing = new User();
        existing.setId(7L);
        existing.setUsername("alice");
        when(userMapper.selectOne(any())).thenReturn(existing);
        DepositWallet wallet = new DepositWallet();
        wallet.setAddress("0xdef0000000000000000000000000000000000002");
        when(depositWalletService.findUserWallet(CHAIN_ID, 7L)).thenReturn(wallet);

        UserService.Registered registered = service.register("alice");

        assertThat(registered.userId()).isEqualTo(7L);
        assertThat(registered.depositAddress())
                .isEqualTo("0xdef0000000000000000000000000000000000002");
        verify(userMapper, never()).insert(any(User.class));
        verify(depositWalletService, never()).saveWallet(any(DepositWallet.class));
    }

    @Test
    void 查询不存在用户的地址应报错() {
        assertThatThrownBy(() -> service.depositAddress(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户不存在");
    }
}
