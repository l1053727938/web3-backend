package com.ray.web3.service.impl;

import com.ray.web3.config.BackfillProperties;
import com.ray.web3.config.InfuraProperties;
import com.ray.web3.mapper.TransferEventLogMapper;
import com.ray.web3.model.DepositConsts;
import com.ray.web3.model.dto.TransferEventDTO;
import com.ray.web3.model.vo.BackfillResultVO;
import com.ray.web3.service.DepositRoutingService;
import com.ray.web3.service.EventLogService;
import com.ray.web3.service.HistoryBackfillService;
import com.ray.web3.service.SyncCursorService;
import com.ray.web3.service.TransferEventStoreService;
import com.ray.web3.util.ChainParams;
import com.ray.web3.util.ChainQueryUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.util.List;

/**
 * 历史补拉编排实现（模块二）：断点续传 + 安全分页 + 分批解码 + 游标同事务推进
 * <p>
 * 关键取舍：
 * 1. 链上高度截断在本层完成（fetchTransferLogPage 不截断），避免页边界被偷改后游标推过头；
 * 2. 断点续传：已有游标且覆盖 fromBlock 时从 cursor+1 续拉，否则从 fromBlock 起（首次补拉即 fromBlock）；
 * 3. maxBlocksPerRun 限幅：单次只推进有限区块，返回 limited=true，同区间重调自然续拉；
 * 4. 同合约 Redisson 互斥：两个任务跑同一区间只会白白消耗 RPC 配额，抢不到锁直接失败比排队更有信息量
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "web3.backfill.enabled", havingValue = "true", matchIfMissing = true)
public class HistoryBackfillServiceImpl implements HistoryBackfillService {

    private static final String LOCK_KEY_PREFIX = "web3:backfill:lock:";

    private final Web3j web3j;
    private final EventLogService eventLogService;
    private final TransferEventStoreService transferEventStoreService;
    private final SyncCursorService syncCursorService;
    private final TransferEventLogMapper transferEventLogMapper;
    private final InfuraProperties infuraProperties;
    private final BackfillProperties backfillProperties;
    private final RedissonClient redissonClient;

    /** 充值路由（模块四）受 web3.deposit.enabled 控制，用 ObjectProvider 延迟取得，关闭时不影响补拉 */
    private final ObjectProvider<DepositRoutingService> depositRoutingService;

    @Override
    public BackfillResultVO backfill(String contractAddress, BigInteger fromBlock, BigInteger toBlock, Integer chunkSize) {
        ChainParams.requireContract(contractAddress);
        ChainParams.requireRange(fromBlock, toBlock);

        String contract = contractAddress.toLowerCase();
        int chunk = (chunkSize != null && chunkSize > 0) ? chunkSize : backfillProperties.getChunkSize();

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + infuraProperties.getChainId() + ":" + contract);
        if (!lock.tryLock()) {
            throw new IllegalStateException("该合约历史补拉任务正在进行中，请稍后重试: " + contract);
        }
        try {
            return doBackfill(contract, fromBlock, toBlock, chunk);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public Long currentCursor(String contractAddress) {
        ChainParams.requireContract(contractAddress);
        return syncCursorService.readCursor(contractAddress.toLowerCase());
    }

    @Override
    public Long savedTransferCount(String contractAddress, BigInteger fromBlock, BigInteger toBlock) {
        ChainParams.requireContract(contractAddress);
        ChainParams.requireRange(fromBlock, toBlock);
        return transferEventLogMapper.countInRange(
                infuraProperties.getChainId(), contractAddress.toLowerCase(),
                fromBlock.longValue(), toBlock.longValue());
    }

    /**
     * 抢锁后的实际补拉：解析有效区间 → 分页循环拉取/解码/落库/推进游标
     */
    private BackfillResultVO doBackfill(String contract, BigInteger fromBlock, BigInteger toBlock, int chunk) {
        // 结束区块以链上高度截断（本层负责，页拉取层不截断）
        BigInteger latest = ChainQueryUtils.latestBlockNumber(web3j);
        long endBlock = toBlock.min(latest).longValue();

        // 断点续传起点：已有游标且落在 [fromBlock, ...] 内时从游标 +1 续拉，否则从 fromBlock 起
        Long cursor = syncCursorService.readCursor(contract);
        long startBlock = (cursor != null && cursor >= fromBlock.longValue()) ? cursor + 1 : fromBlock.longValue();

        // 区间已完整处理过（游标已推进到 toBlock 及以后）：无需拉取，直接回现状
        if (startBlock > endBlock) {
            log.info("补拉跳过 contract={} 区间[{},{}] 已被游标 {} 覆盖", contract, fromBlock, toBlock, cursor);
            long processed = cursor != null ? cursor : endBlock;
            return new BackfillResultVO(contract, startBlock, endBlock, 0, 0, 0, processed, false);
        }

        // 单次时长限幅：把大区间拆成多次调用，下次从游标自动续拉
        boolean limited = false;
        long max = backfillProperties.getMaxBlocksPerRun();
        if (max > 0 && (endBlock - startBlock + 1) > max) {
            endBlock = startBlock + max - 1;
            limited = true;
        }

        int pages = 0;
        long fetched = 0;
        long saved = 0;
        long lastProcessed = startBlock - 1;

        for (long pageFrom = startBlock; pageFrom <= endBlock; ) {
            long pageTo = Math.min(pageFrom + chunk - 1, endBlock);

            // 拉取并解码一个分页（页内命中节点上限由 EventLogService 自动缩跨度续拉，保证页完整）
            List<TransferEventDTO> pageEvents = eventLogService.fetchTransferLogPage(
                    contract, BigInteger.valueOf(pageFrom), BigInteger.valueOf(pageTo));

            // 事件落库 + 游标推进同事务（幂等：重放区间 saved=0）
            long pageSaved = transferEventStoreService.savePage(contract, pageTo, pageEvents);
            // 事务提交后刷新 Redis 读加速副本（滞后不影响正确性，下次回源自愈）
            syncCursorService.cacheCursor(contract, pageTo);
            // 模块四充值路由：必须在落库事务提交后执行，补拉重放区间靠唯一键幂等不会重复入账
            routeDeposits(pageEvents);

            pages++;
            fetched += pageEvents.size();
            saved += pageSaved;
            lastProcessed = pageTo;

            log.info("补拉分页 contract={} page=[{},{}] events={} saved={} 进度 {}/{}",
                    contract, pageFrom, pageTo, pageEvents.size(), pageSaved, lastProcessed, endBlock);

            pageFrom = pageTo + 1;
        }

        log.info("补拉完成 contract={} range=[{},{}] pages={} fetched={} saved={} cursor={} limited={}",
                contract, startBlock, endBlock, pages, fetched, saved, lastProcessed, limited);
        return new BackfillResultVO(contract, startBlock, endBlock, pages, fetched, saved, lastProcessed, limited);
    }

    /**
     * 模块四充值路由：路由异常只记日志，不能因账务侧（DB/Redis）故障中断整段补拉、留下游标空洞
     */
    private void routeDeposits(List<TransferEventDTO> pageEvents) {
        DepositRoutingService service = depositRoutingService.getIfAvailable();
        if (service == null || pageEvents.isEmpty()) {
            return;
        }
        try {
            service.route(pageEvents, DepositConsts.Source.BACKFILL);
        } catch (Exception e) {
            log.error("[deposit] 补拉充值路由失败 {} 条（流水已入库，可重跑本区间补齐）: {}",
                    pageEvents.size(), e.getMessage(), e);
        }
    }
}
