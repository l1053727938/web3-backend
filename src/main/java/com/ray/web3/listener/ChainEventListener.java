package com.ray.web3.listener;

import com.ray.web3.config.WebSocketProperties;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.websocket.WebSocketService;
import org.web3j.protocol.websocket.events.Log;
import org.web3j.protocol.websocket.events.LogNotification;
import org.web3j.protocol.websocket.events.NewHeadsNotification;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * WebSocket 实时订阅监听器：newHeads 新区块头 + 目标合约 Transfer 日志
 * <p>
 * 两个订阅均走 eth_subscribe（newHeadsNotifications / logsNotifications），
 * 节点在新数据产生时主动推过来，回调在 WebSocket 读线程上执行；
 * 退订由 CompositeDisposable 统一管理，dispose 时自动发 eth_unsubscribe
 * <p>
 * 注：eth_subscribe("logs") 只按 address + topics 过滤，无区块区间概念，
 * 也不回报链重组（被撤销的日志不会通知），因此本监听只做“提前发现”，
 * 落库仍以 BlockScanner 的 eth_getLogs 区间重扫为准
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "web3.websocket.enabled", havingValue = "true")
public class ChainEventListener {

    /** ERC-20 Transfer(address,address,uint256) 事件签名哈希 */
    private static final String TOPIC_TRANSFER = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private final Web3j wsWeb3j;
    private final WebSocketService webSocketService;
    private final WebSocketProperties properties;

    /** 组合容器统一管理所有订阅，关闭时一次性 dispose */
    private final CompositeDisposable disposables = new CompositeDisposable();

    public ChainEventListener(@Qualifier("wsWeb3j") Web3j wsWeb3j,
                              WebSocketService webSocketService,
                              WebSocketProperties properties) {
        this.wsWeb3j = wsWeb3j;
        this.webSocketService = webSocketService;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startSubscriptions() {
        if (properties.isSubscribeNewHeads()) {
            subscribeNewHeads();
        }
        subscribeLogs();
    }

    /**
     * 订阅 newHeads：每个新区块打包完成即推送一条区块头
     */
    private void subscribeNewHeads() {
        Disposable d = wsWeb3j.newHeadsNotifications()
                .subscribe(
                        this::onNewHead,
                        err -> log.error("newHeads 订阅异常", err));
        disposables.add(d);
        log.info("已订阅 newHeads");
    }

    private void onNewHead(NewHeadsNotification notification) {
        var head = notification.getParams().getResult();
        BigInteger number = Numeric.toBigInt(head.getNumber());
        BigInteger timestamp = Numeric.toBigInt(head.getTimestamp());
        log.info("[newHeads] #{} hash={} gasUsed={} at={}",
                number, head.getHash(), head.getGasUsed(),
                Instant.ofEpochSecond(timestamp.longValue()));
    }

    /**
     * 订阅 logs：改用 logsNotifications（内部为 eth_subscribe("logs", {address, topics})），
     * 与旧版 ethLogFlowable 的区别：后者是 eth_newFilter + 每 15s 轮询 eth_getFilterChanges
     * <p>
     * watch-contracts 为空时传空集合（监听全链 Transfer，量大慎用）：
     * web3j 的 createLogsParams 直接对入参调 isEmpty()，传 null 会 NPE，不能沿用旧版的 null 写法
     */
    private void subscribeLogs() {
        List<String> contracts =
                Optional.ofNullable(properties.getWatchContracts()).orElse(Collections.emptyList());
        Disposable d = wsWeb3j.logsNotifications(contracts, Collections.singletonList(TOPIC_TRANSFER))
                .subscribe(
                        this::onLog,
                        err -> log.error("logs 订阅异常", err));
        disposables.add(d);
        log.info("已订阅 Transfer 日志（eth_subscribe logs） contracts={}",
                contracts.isEmpty() ? "<全部合约>" : contracts);
    }

    /**
     * websocket.events.Log 的字段全是原始 hex 字符串（与 core.methods.response.Log 不同，
     * 后者 getBlockNumber() 已返回 BigInteger），需自行解码
     */
    private void onLog(LogNotification notification) {
        Log logObject = notification.getParams().getResult();
        List<String> topics = logObject.getTopics();
        String from = topics.size() > 1 ? extractAddress(topics.get(1)) : "?";
        String to = topics.size() > 2 ? extractAddress(topics.get(2)) : "?";
        BigInteger value = Numeric.toBigInt(logObject.getData());
        log.info("[logs] block=#{} tx={} {} -> {} value={} (contract={})",
                Numeric.toBigInt(logObject.getBlockNumber()), logObject.getTransactionHash(),
                from, to, value, logObject.getAddress());
    }

    /**
     * 从 32 字节 topic 中提取末尾 20 字节的地址
     */
    private String extractAddress(String topic) {
        return "0x" + topic.substring(topic.length() - 40);
    }

    /**
     * 释放资源：先 dispose 全部订阅（退订），再关闭底层 WebSocket 连接；
     * wsWeb3j.shutdown() 由其 Bean destroyMethod 在这之后执行
     */
    @PreDestroy
    public void stopSubscriptions() {
        disposables.dispose();
        log.info("已退订全部 Flowable 订阅");
        webSocketService.close();
        log.info("WebSocket 连接已关闭");
    }
}
