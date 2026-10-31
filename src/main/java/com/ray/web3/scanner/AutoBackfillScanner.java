package com.ray.web3.scanner;

import com.ray.web3.config.BackfillProperties;
import com.ray.web3.config.WebSocketProperties;
import com.ray.web3.consts.PatternConsts;
import com.ray.web3.model.vo.BackfillResultVO;
import com.ray.web3.service.HistoryBackfillService;
import com.ray.web3.service.SyncCursorService;
import com.ray.web3.util.ChainQueryUtils;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 定时自动核对扫描器（模块二"定时批量核对"的自动触发通道）
 * <p>
 * 业务定位：实时订阅链路只保证"尽早发现"，短时事件洪峰被丢弃、断线重连漏推等缺口，
 * 由本扫描器周期性把各合约从<b>游标位置</b>补拉到链上最新高度，保证"一笔不漏"；
 * 复用 {@link HistoryBackfillService#backfill} 的全部保障（游标断点续传、幂等落库、
 * 落库事务提交后充值路由、单合约互斥锁），与手动区间核对走同一条核对口径。
 * <p>
 * 关键设计：
 * 1. 触发通道与 BlockScanner 同款双通道：Spring Scheduler（默认）+ XXL-Job（接入调度中心后分布式触发）；
 * 2. 全局 Redisson 锁 tryLock：多实例仅一个节点执行；与手动补拉撞车时直接让位下一轮
 *    （补拉层本就有合约级互斥 + 游标幂等，让位只是省掉一次注定失败的抢锁 RPC 开销）；
 * 3. 单合约异常只记录不中断：一个合约节点侧失败不影响其余合约继续核对；
 * 4. 无游标的合约从 auto-from-block 起步，配置为负数则跳过——历史起点留给人工裁决，
 *    防止定时任务对未初始化合约发起全链历史扫描打爆 RPC 配额；
 * 5. 补拉服务受 web3.backfill.enabled 控制，用 ObjectProvider 延迟取得，关闭时不影响启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "web3.backfill.auto-enabled", havingValue = "true", matchIfMissing = true)
public class AutoBackfillScanner {

    private static final String LOCK_KEY = "web3:backfill:lock:auto";

    private final Web3j web3j;
    /** 补拉服务受 web3.backfill.enabled 控制，关闭时自动核对空转不报错 */
    private final ObjectProvider<HistoryBackfillService> historyBackfillService;
    private final SyncCursorService syncCursorService;
    private final BackfillProperties backfillProperties;
    private final WebSocketProperties webSocketProperties;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelayString = "${web3.backfill.auto-interval-ms:30000}")
    public void autoBackfillByScheduler() {
        doAutoBackfill("spring-scheduler");
    }

    @XxlJob("autoBackfillHandler")
    public void autoBackfillByXxlJob() {
        doAutoBackfill("xxl-job");
    }

    private void doAutoBackfill(String trigger) {
        HistoryBackfillService service = historyBackfillService.getIfAvailable();
        if (service == null) {
            log.debug("[{}] 补拉服务未启用（web3.backfill.enabled=false），跳过自动核对", trigger);
            return;
        }
        RLock lock = redissonClient.getLock(LOCK_KEY);
        if (!lock.tryLock()) {
            log.debug("[{}] 其他实例正在自动核对，跳过本轮", trigger);
            return;
        }
        try {
            BigInteger latest = ChainQueryUtils.latestBlockNumber(web3j);
            Set<String> contracts = resolveContracts();
            if (contracts.isEmpty()) {
                log.warn("[{}] 自动核对跳过：未配置 web3.backfill.auto-contracts 且 web3.websocket.watch-contracts 为空", trigger);
                return;
            }
            log.info("[{}] 自动核对开始 latestBlock={} contracts={}", trigger, latest, contracts);
            for (String contract : contracts) {
                backfillOne(service, contract, latest);
            }
        } catch (Exception e) {
            log.warn("[{}] 自动核对轮次失败: {}", trigger, e.getMessage());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 单合约补拉到最新高度：起点优先取游标 +1（断点续传），无游标按 auto-from-block 起步；
     * 异常只记日志，不让一个合约的故障拖垮整轮核对
     */
    private void backfillOne(HistoryBackfillService service, String contract, BigInteger latest) {
        try {
            Long cursor = syncCursorService.readCursor(contract);
            BigInteger from = (cursor != null) ? BigInteger.valueOf(cursor + 1)
                    : BigInteger.valueOf(backfillProperties.getAutoFromBlock());
            if (from.compareTo(latest) > 0) {
                return; // 已追平链上高度，无需拉取
            }
            if (cursor == null && backfillProperties.getAutoFromBlock() < 0) {
                log.warn("[auto-backfill] contract={} 无游标且 auto-from-block<0，跳过（请先手动补拉建立游标）", contract);
                return;
            }
            BackfillResultVO result = service.backfill(
                    contract, from, latest, backfillProperties.getChunkSize());
            if (result.pages() > 0) {
                log.info("[auto-backfill] contract={} 区间[{},{}] pages={} fetched={} saved={} cursor={}",
                        contract, result.startBlock(), result.endBlock(),
                        result.pages(), result.fetchedCount(), result.savedCount(), result.lastProcessedBlock());
            } else {
                log.debug("[auto-backfill] contract={} 区间[{},{}] 无新增事件（已追平）",
                        contract, result.startBlock(), result.endBlock());
            }
        } catch (Exception e) {
            log.error("[auto-backfill] contract={} 核对失败（下一轮自动重试）: {}", contract, e.getMessage(), e);
        }
    }

    /**
     * 核对合约清单：auto-contracts 优先，留空回退实时订阅的 watch-contracts；
     * 归一小写、格式非法项剔除、去重保序
     */
    private Set<String> resolveContracts() {
        List<String> configured = backfillProperties.getAutoContracts();
        if (configured == null || configured.isEmpty()) {
            configured = webSocketProperties.getWatchContracts();
        }
        Set<String> result = new LinkedHashSet<>();
        if (configured == null) {
            return result;
        }
        for (String c : configured) {
            if (!PatternConsts.isLooseAddress(c)) {
                log.warn("[auto-backfill] 忽略非法合约地址: {}", c);
                continue;
            }
            result.add(c.toLowerCase());
        }
        return result;
    }
}
