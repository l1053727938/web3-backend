package com.ray.web3.listener;

import com.ray.web3.config.WebSocketProperties;
import com.ray.web3.config.WebSocketServiceFactory;
import com.ray.web3.model.DepositConsts;
import com.ray.web3.model.dto.TransferEventDTO;
import com.ray.web3.service.DepositRoutingService;
import com.ray.web3.service.TokenMetadataService;
import com.ray.web3.service.TransferEventStoreService;
import com.ray.web3.util.ChainQueryUtils;
import com.ray.web3.util.TransferEventDecoder;
import io.reactivex.disposables.Disposable;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.websocket.WebSocketService;
import org.web3j.protocol.websocket.events.LogNotification;
import org.web3j.protocol.websocket.events.NewHeadsNotification;
import org.web3j.utils.Numeric;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket 实时订阅引擎（REQ-STREAM-03，模块三）
 * <p>
 * 链路：自建 wss 长连接 → {@code ethLogFlowable(EthFilter)} 订阅目标合约 Transfer → onNext 双重校验
 * {@code isRemoved()}（丢弃因链重组被撤销的日志）+ 模块一解码 → 推入内存队列 → 独立工作线程批量落库
 * → 模块四充值路由（命中平台地址写充值记录并通知下游）。
 * <p>
 * 关键设计（与需求文字的两处落地取舍）：
 * 1. onNext 回调只做「解码 + 入队」这类轻操作，耗时 DB 写交给工作线程，绝不阻塞 web3j 轮询线程；
 *    队列满时丢弃并告警——实时事件定位是"提前发现"，区间完整性由模块二按游标重扫兜底。
 * 2. 断线重连：{@code ethLogFlowable} 内部吞掉轮询异常、不会向下游 onError，因此重连触发点取自
 *    {@code WebSocketService} 的 onClose/onError 回调；且走 HTTP 代理隧道时 reconnectBlocking 无法复用
 *    已关闭的 socket，故每次重连都经工厂<b>重建</b>整套 service/web3j 并重新订阅，指数退避直到成功。
 * 3. newHeads 心跳辅助判定：onClose/onError 只能探测“ socket 已断”，看不到“连接活着但节点不再推数据”
 *    的僵尸态；引擎在自身 WS 连接上另订阅 newHeads 作为链的心跳，看门狗发现超时未出块时，
 *    先用 HTTP 通道复核链上高度——链在前进而 WS 没推到才算僵尸，触发重建重连；
 *    链本身空闲（如 Anvil 按需出块）则只重置计时不误伤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "web3.websocket.enabled", havingValue = "true")
public class TransferStreamEngine {

    /** 重连退避序列的指数上限（2^N，避免溢出即足以覆盖 max 封顶） */
    private static final int MAX_BACKOFF_SHIFT = 20;

    private final WebSocketProperties properties;
    private final WebSocketServiceFactory serviceFactory;
    private final TokenMetadataService tokenMetadataService;
    private final TransferEventStoreService transferEventStoreService;

    /** 充值路由（模块四）受 web3.deposit.enabled 控制，用 ObjectProvider 延迟取得，关闭时不影响启动 */
    private final ObjectProvider<DepositRoutingService> depositRoutingService;

    /** HTTP 主通道（Bean 名 web3j，与引擎内部按名新建的 WS 实例区分）：心跳僵尸判定时复核链上高度用 */
    private final Web3j web3j;

    /** 解码后的事件缓冲队列（有界，满则丢弃告警，保护轮询线程） */
    private BlockingQueue<TransferEventDTO> eventQueue;

    /** 当前连接三件套：重连时整体重建 */
    private volatile WebSocketService webSocketService;
    private volatile Web3j wsClient;
    private volatile Disposable subscription;

    /** newHeads 心跳：最近一次收到推送的区块/时间戳，及挂在引擎自身 WS 连接上的心跳订阅 */
    private volatile long lastHeartbeatBlock = -1L;
    private volatile long lastHeartbeatAt;
    private volatile Disposable heartbeatSubscription;

    /** 重连调度器（单线程，串行化所有连接/订阅操作）+ 工作线程 + 生命周期锁 */
    private ScheduledExecutorService reconnectExecutor;
    private Thread workerThread;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private volatile int reconnectAttempt = 0;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        this.eventQueue = new LinkedBlockingQueue<>(Math.max(16, properties.getEventQueueCapacity()));
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-reconnect");
            t.setDaemon(true);
            return t;
        });
        this.workerThread = new Thread(this::consumeLoop, "transfer-db-worker");
        this.workerThread.setDaemon(true);
        this.workerThread.start();

        // 首连也走调度器，避免 connectBlocking 阻塞容器启动主线程
        reconnectExecutor.execute(this::connectAndSubscribe);
        if (properties.isHeartbeatEnabled()) {
            // 与连接/重连同一单线程串行：检查与重连动作天然互斥，无并发竞态
            reconnectExecutor.scheduleWithFixedDelay(this::checkHeartbeat,
                    properties.getHeartbeatCheckIntervalMs(), properties.getHeartbeatCheckIntervalMs(), TimeUnit.MILLISECONDS);
        }
        log.info("Transfer 实时订阅引擎已启动 url={} contracts={} pollingMs={}ms heartbeat={}",
                properties.getUrl(),
                properties.getWatchContracts(),
                properties.getPollingIntervalMs(),
                properties.isHeartbeatEnabled() ? properties.getHeartbeatTimeoutMs() + "ms" : "off");
    }

    /**
     * 建立连接并订阅：关旧连接 → 工厂重建 service/web3j → connect(挂 onClose/onError 回调) → ethLogFlowable 订阅。
     * 任一步失败按退避重试（重连与首连同一入口）。
     */
    private void connectAndSubscribe() {
        if (!running.get()) {
            return;
        }
        synchronized (lifecycleLock) {
            if (!running.get()) {
                return;
            }
            closeCurrentQuietly();
            try {
                WebSocketService service = serviceFactory.createService(properties);
                Web3j client = serviceFactory.createWeb3j(service, properties.getPollingIntervalMs());
                // 连接失败会走 scheduleReconnect；重连成功后挂回 onClose/onError 以持续监控断线
                service.connect(log::info, err -> onConnectionDown("error: " + err.getMessage()),
                        () -> onConnectionDown("socket closed"));
                this.webSocketService = service;
                this.wsClient = client;

                subscribe(client);
                subscribeHeartbeat(client);

                // 刚建好的连接还没有 newHeads 推过来，从当前时刻重新起算，避免重连瞬间被旧超时误判
                refreshHeartbeat();
                reconnectAttempt = 0;
                reconnectScheduled.set(false);
                log.info("WebSocket 已连接并订阅 Transfer 事件");
            } catch (Exception e) {
                log.warn("建立 WebSocket 连接/订阅失败: {}", e.getMessage());
                closeCurrentQuietly();
                scheduleReconnect();
            }
        }
    }

    /**
     * 构建订阅：topic0 复用模块一 {@link TransferEventDecoder#transferEventTopic()} 单一来源；
     * logsNotifications 走 WebSocket 推送（eth_subscribe），无需 EthFilter 区块范围
     */
    private void subscribe(Web3j client) {
        /*this.subscription = client.logsNotifications(watchAddresses(), Collections.singletonList(TransferEventDecoder.transferEventTopic()))
                .subscribe(this::onLog, err -> {
                    // eth_newFilter/getFilterLogs 建过滤器失败会走到这里；轮询期异常被 web3j 内部吞掉，故主要靠 onClose 重连
                    log.warn("Transfer 订阅 Flowable 异常: {}", err.getMessage());
                    onConnectionDown("flowable error");
                });*/
        EthFilter filter = new EthFilter(DefaultBlockParameterName.LATEST,DefaultBlockParameterName.LATEST, watchAddresses());
        filter.addSingleTopic(TransferEventDecoder.transferEventTopic());
        this.subscription = client.ethLogFlowable(filter)
                .subscribe(this::onLog, err -> {
                    // eth_newFilter/getFilterLogs 建过滤器失败会走到这里；轮询期异常被 web3j 内部吞掉，故主要靠 onClose 重连
                    log.warn("Transfer 订阅 Flowable 异常: {}", err.getMessage());
                    onConnectionDown("flowable error");
                });
    }

    /**
     * newHeads 心跳订阅：挂在引擎<b>自身</b> WS 连接上（挂共享 wsWeb3j 探测不到本引擎连接的僵尸态）。
     * 心跳只是辅助信号，订阅失败不影响主链路：退订/重建后下一轮重连会重新挂上
     */
    private void subscribeHeartbeat(Web3j client) {
        if (!properties.isHeartbeatEnabled()) {
            return;
        }
        try {
            this.heartbeatSubscription = client.newHeadsNotifications()
                    .subscribe(this::onHeartbeat,
                            err -> log.warn("[heartbeat] newHeads 订阅异常（仅失去心跳信号，不影响 Transfer 订阅）: {}",
                                    err.getMessage()));
        } catch (Exception e) {
            log.warn("[heartbeat] 订阅 newHeads 失败（不影响主链路）: {}", e.getMessage());
        }
    }

    /** newHeads 推送到达：记录链位与时间戳，看门狗据此判定连接是否还活着 */
    private void onHeartbeat(NewHeadsNotification notification) {
        try {
            this.lastHeartbeatBlock = Numeric.toBigInt(notification.getParams().getResult().getNumber()).longValue();
        } catch (Exception e) {
            // 高度解析失败不影响心跳本质（时间戳才是判定依据）
            log.debug("[heartbeat] 解析区块高度失败: {}", e.getMessage());
        }
        this.lastHeartbeatAt = System.currentTimeMillis();
    }

    /**
     * 心跳看门狗（与连接/重连同一串行调度线程，无需额外加锁）：
     * 1. 已有断开重连在途/已判僵尸 → 不重复触发；连接尚未建立 → 本轮跳过；
     * 2. 超时未收到 newHeads 时用 HTTP 主通道复核：链高度高于最后心跳块 = 链在前进而 WS 没推到，
     *    判僵尸触发重建；高度没变 = 链本身空闲（Anvil 按需出块等），只重置计时不误伤；
     * 3. 复核 RPC 也异常 = 整条链通道都不通，同样触发重连重建
     */
    private void checkHeartbeat() {
        if (!properties.isHeartbeatEnabled() || !running.get()
                || reconnectScheduled.get() || wsClient == null) {
            return;
        }
        long last = lastHeartbeatAt;
        if (last == 0L || System.currentTimeMillis() - last < properties.getHeartbeatTimeoutMs()) {
            return;
        }
        long silentMs = System.currentTimeMillis() - last;
        try {
            long latest = ChainQueryUtils.latestBlockNumber(web3j).longValue();
            if (latest > lastHeartbeatBlock) {
                log.warn("[heartbeat] 已 {}ms 未收到 newHeads，但 HTTP 复核链已从 {} 前进到 {}，"
                                + "判定 WS 僵尸连接，触发重建重连", silentMs, lastHeartbeatBlock, latest);
                onConnectionDown("heartbeat stale");
            } else {
                log.debug("[heartbeat] 已 {}ms 未收到 newHeads，链高度仍为 {}（空闲链），重置计时", silentMs, latest);
                refreshHeartbeat();
            }
        } catch (Exception e) {
            log.warn("[heartbeat] 超时且 HTTP 复核也失败（{}），链路整体异常，触发重连", e.getMessage());
            onConnectionDown("heartbeat timeout + rpc fail");
        }
    }

    /** 把心跳基准重置为“现在”：重连成功后、以及确认链空闲后调用 */
    private void refreshHeartbeat() {
        this.lastHeartbeatAt = System.currentTimeMillis();
    }

    /**
     * onNext（logsNotifications 推送）：WebSocket 日志转 response.Log 后复用 {@link #onLog(Log)}
     * 的「isRemoved 校验 + 模块一解码 + 入队」链路，落库仍由工作线程批量处理
     */
    private void onLog(LogNotification notification) {
        org.web3j.protocol.websocket.events.Log wsLog = notification.getParams().getResult();
        onLog(toRawLog(wsLog));
    }

    /**
     * WebSocket 事件日志（字符串 hex）→ eth_getLogs 同构的 {@link Log}，供模块一解码器复用；
     * hex 字符串由各 setter 内部解析为 BigInteger。WebSocket 推送日志不带移除标记（重组撤销
     * 由模块二重扫兜底），按未移除处理
     */
    private Log toRawLog(org.web3j.protocol.websocket.events.Log wsLog) {
        Log raw = new Log();
        raw.setRemoved(false);
        raw.setLogIndex(wsLog.getLogIndex());
        raw.setTransactionIndex(wsLog.getTransactionIndex());
        raw.setBlockHash(wsLog.getBlockHash());
        raw.setBlockNumber(wsLog.getBlockNumber());
        raw.setTransactionHash(wsLog.getTransactionHash());
        raw.setAddress(wsLog.getAddress());
        raw.setData(wsLog.getData());
        raw.setTopics(wsLog.getTopics());
        return raw;
    }

    /**
     * onNext：isRemoved 双重校验（跳过重组撤销的日志）→ 模块一解码 → 入队，回调线程内不做任何 DB 写
     */
    private void onLog(Log rawLog) {
        if (rawLog.isRemoved()) {
            log.debug("[stream] 跳过被链重组移除的日志 block=#{} tx={}",
                    rawLog.getBlockNumber(), rawLog.getTransactionHash());
            return;
        }
        int decimals = tokenMetadataService.decimals(rawLog.getAddress());
        TransferEventDecoder.decode(rawLog, decimals).ifPresent(dto -> {
            if (!eventQueue.offer(dto)) {
                log.warn("[stream] 事件队列已满(cap={})，丢弃 block=#{} tx={}（区间完整性由模块二重扫兜底）",
                        properties.getEventQueueCapacity(), dto.blockNumber(), dto.transactionHash());
            } else {
                log.info("[stream] 事件入队 block=#{} tx={} {} -> {} contract={}",
                        dto.blockNumber(), dto.transactionHash(), dto.from(), dto.to(), dto.contract());
            }
        });
    }

    /**
     * 工作线程：从队列攒批取事件写库（复用模块二落库，幂等 INSERT IGNORE），与回调线程彻底解耦
     * <p>
     * 充值路由（模块四）挂在落库<b>之后</b>：saveEvents 事务已提交，此时才允许对下游发布
     */
    private void consumeLoop() {
        int batchSize = Math.max(1, properties.getWorkerBatchSize());
        List<TransferEventDTO> batch = new ArrayList<>(batchSize);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                TransferEventDTO first = eventQueue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                eventQueue.drainTo(batch, batchSize - 1);
                long saved = transferEventStoreService.saveEvents(batch);
                log.debug("[stream] 批量落库 events={} saved={}", batch.size(), saved);
                routeDeposits(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 落库失败不重入队，避免坏数据死循环；本批丢弃，缺失由模块二按游标重扫补齐
                log.error("[stream] 实时落库失败，丢弃本批 {} 条: {}", batch.size(), e.getMessage(), e);
            }
        }
        // 退出前尽量排空剩余缓冲
        List<TransferEventDTO> rest = new ArrayList<>();
        eventQueue.drainTo(rest);
        if (!rest.isEmpty()) {
            try {
                transferEventStoreService.saveEvents(rest);
                routeDeposits(rest);
            } catch (Exception e) {
                log.warn("[stream] 关停前排空落库失败 {} 条: {}", rest.size(), e.getMessage());
            }
        }
    }

    /**
     * 模块四充值路由：单独兜异常，业务侧故障（DB/Redis）不能拖垮实时订阅主链路
     */
    private void routeDeposits(List<TransferEventDTO> events) {
        DepositRoutingService service = depositRoutingService.getIfAvailable();
        if (service == null || events.isEmpty()) {
            return;
        }
        try {
            service.route(events, DepositConsts.Source.STREAM);
        } catch (Exception e) {
            log.error("[deposit] 实时充值路由失败 {} 条（流水已入库，由补拉链路重放补齐）: {}",
                    events.size(), e.getMessage(), e);
        }
    }

    /**
     * 连接不可用（onClose/onError/Flowable 异常）时按退避重连；去重避免并发风暴
     */
    private void onConnectionDown(String reason) {
        if (!running.get()) {
            return;
        }
        log.warn("检测到 WebSocket 断开({})，准备重连", reason);
        if (properties.isReconnectEnabled()) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        int attempt = reconnectAttempt;
        long delay = Math.min(backoffBase(attempt), properties.getReconnectMaxDelayMs());
        reconnectAttempt = Math.min(attempt + 1, MAX_BACKOFF_SHIFT);
        log.info("将在 {}ms 后重连（第 {} 次）", delay, attempt + 1);
        reconnectExecutor.schedule(() -> {
            reconnectScheduled.set(false);
            connectAndSubscribe();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private long backoffBase(int attempt) {
        long initial = Math.max(200L, properties.getReconnectInitialDelayMs());
        int shift = Math.min(attempt, MAX_BACKOFF_SHIFT);
        return initial << shift;
    }

    /**
     * 释放当前连接三件套：先退订（dispose 可能因连接已死触发 uninstall 异常，吞掉），再关 web3j 与服务
     */
    private void closeCurrentQuietly() {
        Disposable hb = this.heartbeatSubscription;
        if (hb != null && !hb.isDisposed()) {
            try {
                hb.dispose();
            } catch (Exception e) {
                log.debug("退订 newHeads 心跳异常（连接可能已断开）: {}", e.getMessage());
            }
        }
        this.heartbeatSubscription = null;

        Disposable sub = this.subscription;
        if (sub != null && !sub.isDisposed()) {
            try {
                sub.dispose();
            } catch (Exception e) {
                log.debug("退订 Transfer 订阅异常（连接可能已断开）: {}", e.getMessage());
            }
        }
        this.subscription = null;

        Web3j client = this.wsClient;
        if (client != null) {
            try {
                client.shutdown();
            } catch (Exception e) {
                log.debug("关闭 wsWeb3j 异常: {}", e.getMessage());
            }
        }
        this.wsClient = null;

        WebSocketService service = this.webSocketService;
        if (service != null) {
            try {
                service.close();
            } catch (Exception e) {
                log.debug("关闭 WebSocketService 异常: {}", e.getMessage());
            }
        }
        this.webSocketService = null;
    }

    /**
     * 订阅合约列表（小写）；watchContracts 为空时返回空列表 → EthFilter 地址维度不过滤（全链 Transfer）
     */
    private List<String> watchAddresses() {
        List<String> contracts = properties.getWatchContracts();
        if (contracts == null || contracts.isEmpty()) {
            return null;
        }
        List<String> lower = new ArrayList<>(contracts.size());
        for (String c : contracts) {
            if (c != null && !c.isBlank()) {
                lower.add(c.toLowerCase());
            }
        }
        return lower.isEmpty() ? null : lower;
    }

    @PreDestroy
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("正在关停 Transfer 实时订阅引擎 at={}", Instant.now());
        synchronized (lifecycleLock) {
            closeCurrentQuietly();
        }
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
        }
        if (workerThread != null) {
            workerThread.interrupt();
        }
        log.info("Transfer 实时订阅引擎已关停");
    }
}
