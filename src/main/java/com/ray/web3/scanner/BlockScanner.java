package com.ray.web3.scanner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ray.web3.config.InfuraProperties;
import com.ray.web3.mapper.BlockSyncStateMapper;
import com.ray.web3.model.entity.BlockSyncState;
import com.ray.web3.util.ChainQueryUtils;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;

/**
 * 区块扫描器：定时轮询最新区块高度并记录扫描进度
 * <p>
 * 双触发通道：
 * 1. Spring Scheduler（默认，web3.scanner.enabled=true 时生效）
 * 2. XXL-Job（xxl.job.enabled=true 时由调度中心触发，用于分布式补扫）
 * Redisson 分布式锁保证多实例部署时仅一个节点执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "web3.scanner.enabled", havingValue = "true", matchIfMissing = true)
public class BlockScanner {

    private static final String LOCK_KEY = "web3:scanner:lock:block-sync";
    private static final String NATIVE_CHAIN = "";

    private final Web3j web3j;
    private final InfuraProperties infuraProperties;
    private final BlockSyncStateMapper blockSyncStateMapper;
    private final RedissonClient redissonClient;

//    @Scheduled(fixedDelayString = "${web3.scanner.interval-ms:30000}")
    public void scanByScheduler() {
        doScan("spring-scheduler");
    }

    @XxlJob("blockScanHandler")
    public void scanByXxlJob() {
        doScan("xxl-job");
    }

    private void doScan(String trigger) {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        if (!lock.tryLock()) {
            log.debug("[{}] 其他实例正在扫描，跳过本轮", trigger);
            return;
        }
        try {
            BigInteger latest = ChainQueryUtils.latestBlockNumber(web3j);
            Long chainId = infuraProperties.getChainId();

            LambdaQueryWrapper<BlockSyncState> wrapper = new LambdaQueryWrapper<BlockSyncState>()
                    .eq(BlockSyncState::getChainId, chainId)
                    .eq(BlockSyncState::getContractAddress, NATIVE_CHAIN);
            BlockSyncState state = blockSyncStateMapper.selectOne(wrapper);
            if (state == null) {
                state = new BlockSyncState();
                state.setChainId(chainId);
                state.setContractAddress(NATIVE_CHAIN);
                state.setLatestScannedBlock(latest.longValue());
                blockSyncStateMapper.insert(state);
            } else {
                state.setLatestScannedBlock(latest.longValue());
                blockSyncStateMapper.updateById(state);
            }
            log.info("[{}] 扫描完成 chainId={} latestBlock={}", trigger, chainId, latest);
        } catch (Exception e) {
            log.warn("[{}] 区块扫描失败: {}", trigger, e.getMessage());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
