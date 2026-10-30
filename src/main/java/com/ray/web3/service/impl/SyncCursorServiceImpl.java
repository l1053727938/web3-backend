package com.ray.web3.service.impl;

import com.ray.web3.config.BackfillProperties;
import com.ray.web3.config.InfuraProperties;
import com.ray.web3.mapper.BlockSyncStateMapper;
import com.ray.web3.service.SyncCursorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 游标读写实现：读优先 Redis、未命中回源 MySQL 并回填；Redis 不可用时整体降级为直读 MySQL
 * <p>
 * 写侧只维护 Redis 缓存副本，游标事实由 TransferEventStoreService 在落库事务中 upsert，
 * 保证“事件写入”与“游标推进”原子提交，Redis 缓存即使短暂滞后也不影响正确性（下次回源自愈）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncCursorServiceImpl implements SyncCursorService {

    private static final String CURSOR_KEY_PREFIX = "web3:sync:cursor:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final BlockSyncStateMapper blockSyncStateMapper;
    private final InfuraProperties infuraProperties;
    private final BackfillProperties backfillProperties;

    @Override
    public Long readCursor(String contractAddress) {
        String contract = normalize(contractAddress);
        Long chainId = infuraProperties.getChainId();

        if (backfillProperties.isCursorCacheEnabled()) {
            Long cached = readFromCache(chainId, contract);
            if (cached != null) {
                return cached;
            }
        }

        Long dbValue = blockSyncStateMapper.selectCursor(chainId, contract);
        if (dbValue != null && backfillProperties.isCursorCacheEnabled()) {
            writeToCache(chainId, contract, dbValue);
        }
        return dbValue;
    }

    @Override
    public void cacheCursor(String contractAddress, long lastProcessedBlock) {
        if (!backfillProperties.isCursorCacheEnabled()) {
            return;
        }
        writeToCache(infuraProperties.getChainId(), normalize(contractAddress), lastProcessedBlock);
    }

    /**
     * Redis 读取：任意异常（连接失败/类型不符）都吞掉并回源，不让缓存层拖垮补拉主流程
     */
    private Long readFromCache(Long chainId, String contract) {
        try {
            Object value = redisTemplate.opsForValue().get(key(chainId, contract));
            if (value == null) {
                return null;
            }
            // JSON 数值反序列化可能是 Integer/Long，统一按字符串再解析，避免类型分支遗漏
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            log.warn("读取游标缓存失败，回源 MySQL: {}", e.getMessage());
            return null;
        }
    }

    private void writeToCache(Long chainId, String contract, long block) {
        try {
            redisTemplate.opsForValue().set(key(chainId, contract), block);
        } catch (Exception e) {
            log.warn("写入游标缓存失败（不影响已提交的游标）: {}", e.getMessage());
        }
    }

    private String key(Long chainId, String contract) {
        return CURSOR_KEY_PREFIX + chainId + ":" + contract;
    }

    private String normalize(String contractAddress) {
        return contractAddress == null ? null : contractAddress.toLowerCase();
    }
}
