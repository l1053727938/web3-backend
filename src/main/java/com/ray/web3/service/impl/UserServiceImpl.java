package com.ray.web3.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ray.web3.config.InfuraProperties;
import com.ray.web3.consts.DepositConsts;
import com.ray.web3.mapper.UserMapper;
import com.ray.web3.model.entity.DepositWallet;
import com.ray.web3.model.entity.User;
import com.ray.web3.service.DepositWalletService;
import com.ray.web3.service.UserService;
import com.ray.web3.wallet.HdAddressDeriver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户业务实现（第 3 周）
 * <p>
 * 注册只做两件事：建用户 + 分配充值地址，两者在同一业务事务里提交。
 * <p>
 * <b>开户不在注册事务里</b>：{@code account} 属于账本域（账本连接池/账本事务管理器），
 * 与用户表跨连接池无法同事务提交。账户行由充值消费者在首次入账时通过
 * {@code LedgerAccountService.ensureAccount} 幂等创建——少一行账户可以补，不能为了“看起来原子”
 * 去拆账本连接池隔离。
 * <p>
 * 地址分配：index = userId + offset，由 {@link HdAddressDeriver} 派生，
 * 同一用户重复调用得到同一地址；{@code uk_chain_user} 保证并发注册也只落一行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final DepositWalletService depositWalletService;
    private final HdAddressDeriver addressDeriver;
    private final InfuraProperties infuraProperties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Registered register(String username) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }

        User user = findByUsername(name);
        if (user != null) {
            log.info("[user] 重复注册，返回原用户 userId={} username={}", user.getId(), name);
            return assemble(user);
        }

        user = new User();
        user.setUsername(name);
        user.setStatus(1);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发同名注册：唯一键只放过一个，另一个回读原用户（幂等）
            User existing = findByUsername(name);
            if (existing == null) {
                throw e;
            }
            return assemble(existing);
        }
        log.info("[user] 注册成功 userId={} username={}", user.getId(), name);
        return assemble(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String depositAddress(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在: userId=" + userId);
        }
        return ensureWallet(user).getAddress();
    }

    private Registered assemble(User user) {
        return new Registered(user.getId(), user.getUsername(), ensureWallet(user).getAddress());
    }

    /**
     * 分配（或回读）用户充值地址：
     * 地址只由 user_id 决定，先查再建；并发下撞 {@code uk_chain_user} 时回读已存在行。
     */
    private DepositWallet ensureWallet(User user) {
        Long chainId = infuraProperties.getChainId();
        DepositWallet existing = depositWalletService.findUserWallet(chainId, user.getId());
        if (existing != null) {
            return existing;
        }

        DepositWallet wallet = new DepositWallet();
        wallet.setChainId(chainId);
        wallet.setAddress(addressDeriver.address(user.getId()));
        wallet.setWalletType(DepositConsts.WalletType.USER);
        wallet.setUserId(String.valueOf(user.getId()));
        wallet.setDerivationIndex(addressDeriver.index(user.getId()));
        // currency/contract 留空：同一地址接收任意 ERC-20，币种由 token-symbols 按到账合约反查
        wallet.setStatus(1);
        wallet.setRemark("用户注册自动分配");
        try {
            return depositWalletService.saveWallet(wallet);
        } catch (DuplicateKeyException e) {
            DepositWallet concurrent = depositWalletService.findUserWallet(chainId, user.getId());
            if (concurrent == null) {
                throw e;
            }
            return concurrent;
        }
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .last("LIMIT 1"));
    }
}
