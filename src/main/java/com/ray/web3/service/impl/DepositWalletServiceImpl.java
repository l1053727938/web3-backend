package com.ray.web3.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ray.web3.config.DepositProperties;
import com.ray.web3.config.InfuraProperties;
import com.ray.web3.consts.PatternConsts;
import com.ray.web3.mapper.DepositWalletMapper;
import com.ray.web3.model.DepositConsts;
import com.ray.web3.model.entity.DepositWallet;
import com.ray.web3.service.DepositWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 充值地址匹配实现：配置中心热钱包 + DB 用户地址合并成小写地址索引，按 TTL 回源
 * <p>
 * 关键取舍：
 * 1. 匹配走内存 HashMap，不给每个事件打 SELECT——实时链路事件密度高，逐条查询会拖垮落库线程；
 * 2. DB 回源失败时沿用上一份缓存并退避重试，缓存层故障不能把整条充值链路带崩；
 * 3. 配置中心条目与 DB 条目同地址时以 DB 为准（DB 可按链精细控制 status/contract），
 *    保证运维在库里停用地址能真正生效，不会被配置文件"兜底复活"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositWalletServiceImpl implements DepositWalletService {

    private final DepositWalletMapper depositWalletMapper;
    private final InfuraProperties infuraProperties;
    private final DepositProperties depositProperties;

    /** 小写地址 → 配置；volatile 整体替换，读侧无锁 */
    private volatile Map<String, DepositWallet> cache = Collections.emptyMap();
    private volatile long loadedAt = 0L;

    @Override
    public DepositWallet match(String toAddress) {
        String address = normalize(toAddress);
        if (address == null) {
            return null;
        }
        return walletIndex().get(address);
    }

    @Override
    public List<DepositWallet> activeWallets() {
        return new ArrayList<>(walletIndex().values());
    }

    @Override
    public void invalidateCache() {
        loadedAt = 0L;
    }

    @Override
    public DepositWallet saveWallet(DepositWallet wallet) {
        String address = requireAddress(wallet.getAddress());
        wallet.setAddress(address);
        if (wallet.getChainId() == null) {
            wallet.setChainId(infuraProperties.getChainId());
        }
        if (wallet.getWalletType() == null) {
            wallet.setWalletType(DepositConsts.WalletType.HOT);
        }
        if (!DepositConsts.WalletType.isValid(wallet.getWalletType())) {
            throw new IllegalArgumentException("地址类型非法（仅支持 HOT/USER）: " + wallet.getWalletType());
        }
        if (DepositConsts.WalletType.USER.equals(wallet.getWalletType()) && isBlank(wallet.getUserId())) {
            throw new IllegalArgumentException("walletType=USER 时 userId 不能为空");
        }
        if (wallet.getStatus() == null) {
            wallet.setStatus(1);
        }
        if (!isBlank(wallet.getContract())) {
            wallet.setContract(requireAddress(wallet.getContract()));
        }

        // 唯一键 (chain_id, address)：已有则原地更新（保留 id/创建时间），没有则新增
        DepositWallet exist = depositWalletMapper.selectOne(new LambdaQueryWrapper<DepositWallet>()
                .eq(DepositWallet::getChainId, wallet.getChainId())
                .eq(DepositWallet::getAddress, address)
                .last("LIMIT 1"));
        if (exist == null) {
            wallet.setId(null);
            depositWalletMapper.insert(wallet);
        } else {
            wallet.setId(exist.getId());
            wallet.setCreatedAt(exist.getCreatedAt());
            depositWalletMapper.updateById(wallet);
        }
        invalidateCache();
        log.info("充值地址配置已保存 chain={} address={} type={} userId={} contract={} status={}",
                wallet.getChainId(), address, wallet.getWalletType(), wallet.getUserId(),
                wallet.getContract(), wallet.getStatus());
        return wallet;
    }

    @Override
    public boolean disableWallet(Long chainId, String address) {
        String addr = requireAddress(address);
        long chain = chainId == null ? infuraProperties.getChainId() : chainId;
        DepositWallet exist = depositWalletMapper.selectOne(new LambdaQueryWrapper<DepositWallet>()
                .eq(DepositWallet::getChainId, chain)
                .eq(DepositWallet::getAddress, addr)
                .last("LIMIT 1"));
        if (exist == null) {
            return false;
        }
        exist.setStatus(0);
        depositWalletMapper.updateById(exist);
        invalidateCache();
        log.info("充值地址已停用 chain={} address={}", chain, addr);
        return true;
    }

    /**
     * 取缓存索引：过期即回源；回源失败沿用旧索引并在本周期内不再重试（避免 DB 故障时被每个事件重复打）
     */
    private Map<String, DepositWallet> walletIndex() {
        long now = System.currentTimeMillis();
        long ttl = Math.max(1000L, depositProperties.getWalletCacheRefreshMs());
        if (now - loadedAt >= ttl) {
            synchronized (this) {
                if (now - loadedAt >= ttl) {
                    reload(now, ttl);
                }
            }
        }
        return cache;
    }

    private void reload(long now, long ttl) {
        try {
            Map<String, DepositWallet> merged = new LinkedHashMap<>();
            // 配置中心热钱包：无 DB 记录时的兜底来源（适合少量平台归集地址）
            for (Map.Entry<String, String> e : depositProperties.getHotWallets().entrySet()) {
                DepositWallet w = fromConfig(e.getKey(), e.getValue());
                if (w != null) {
                    merged.put(w.getAddress(), w);
                }
            }
            // DB 配置后写，同地址覆盖配置中心条目，保证库里 status/contract 的精细控制生效
            for (DepositWallet w : depositWalletMapper.selectActiveByChain(infuraProperties.getChainId())) {
                String address = normalize(w.getAddress());
                if (address == null) {
                    log.warn("跳过非法充值地址 dbId={} address={}", w.getId(), w.getAddress());
                    continue;
                }
                w.setAddress(address);
                w.setContract(normalize(w.getContract()));
                merged.put(address, w);
            }
            cache = Collections.unmodifiableMap(merged);
            loadedAt = now;
            log.debug("充值地址缓存已刷新 wallets={}", merged.size());
        } catch (Exception e) {
            // 回源失败：沿用旧缓存，同时把时间戳推到当前，ttl 后再试；首次就失败则保持空索引（不产生充值）
            loadedAt = now;
            log.error("加载充值地址配置失败，沿用上一份缓存(size={})，{}ms 后重试: {}", cache.size(), ttl, e.getMessage());
        }
    }

    private DepositWallet fromConfig(String address, String currency) {
        String addr = normalize(address);
        if (addr == null) {
            log.warn("跳过配置中心非法热钱包地址: {}", address);
            return null;
        }
        DepositWallet w = new DepositWallet();
        w.setChainId(infuraProperties.getChainId());
        w.setAddress(addr);
        w.setWalletType(DepositConsts.WalletType.HOT);
        w.setCurrency(isBlank(currency) ? null : currency.trim());
        w.setContract(null);
        w.setStatus(1);
        w.setRemark("配置中心 web3.deposit.hot-wallets");
        return w;
    }

    private String requireAddress(String address) {
        String addr = normalize(address);
        if (addr == null) {
            throw new IllegalArgumentException("地址格式非法: " + address);
        }
        return addr;
    }

    /** 统一小写，避免大小写导致唯一键分裂与匹配漏命中；空串/非法格式返回 null */
    private String normalize(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        if (!PatternConsts.isLooseAddress(trimmed)) {
            return null;
        }
        return trimmed.toLowerCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
