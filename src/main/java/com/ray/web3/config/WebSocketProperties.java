package com.ray.web3.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * WebSocket 实时订阅配置
 * <p>
 * enabled=true 时建立 wss/ws 长连接，订阅 newHeads 与 logs；
 * url 可指向 Infura WebSocket 端点或本地 Anvil（ws://127.0.0.1:8545）
 */
@Data
@ConfigurationProperties(prefix = "web3.websocket")
public class WebSocketProperties {

    /** 是否启用 WebSocket 实时订阅 */
    private boolean enabled = false;

    /** WebSocket RPC 地址，如 ws://127.0.0.1:8545（Anvil）或 wss://mainnet.infura.io/ws/v3/{projectId} */
    private String url;

    /**
     * HTTP 代理地址。web3j 的 WebSocket 底层是 Java-WebSocket，既不读系统代理也不读 JVM 代理参数，
     * 本机需代理访问主网时必须显式配置；留空则直连
     */
    private String proxyHost;

    /** HTTP 代理端口，与 proxy-host 同时有效才生效 */
    private Integer proxyPort;

    /** 是否订阅 newHeads 新区块头 */
    private boolean subscribeNewHeads = true;

    /** 要监听 Transfer 日志的合约地址列表；为空则不过滤地址（全链 Transfer，量大慎用） */
    private List<String> watchContracts = new ArrayList<>();

    // ============ 模块三：WebSocket 实时订阅引擎（ethLogFlowable + 断线重连）参数 ============

    /** 断线是否自动重连（网络抖动导致 socket 关闭后按退避重建连接并重新订阅） */
    private boolean reconnectEnabled = true;

    /** 重连初始退避间隔（毫秒），随后指数递增 */
    private long reconnectInitialDelayMs = 1000L;

    /** 重连退避上限（毫秒），指数递增封顶于此 */
    private long reconnectMaxDelayMs = 30000L;

    /** ethLogFlowable 轮询间隔（毫秒）：越小延迟越低但 RPC 越密；默认 3000 */
    private long pollingIntervalMs = 3000L;

    /** 解码事件内存队列容量：满时丢弃并告警（实时事件以“提前发现”为主，宁可丢回调不可阻塞轮询线程） */
    private int eventQueueCapacity = 4096;

    /** 工作线程单次批量落库的最大条数：攒批降低 DB 写频率，同时限制单批大小 */
    private int workerBatchSize = 200;

    // ============ newHeads 心跳：断线判定的辅助信号（探测“连接活着但不推数据”的僵尸态）============

    /** 是否启用 newHeads 心跳监测：在引擎自身 WS 连接上订阅 newHeads，超时未收到出块则复核后触发重连 */
    private boolean heartbeatEnabled = true;

    /** 心跳超时阈值（毫秒）：连续无 newHeads 推送超过该时长即进入僵尸判定；需大于链的正常出块间隔 */
    private long heartbeatTimeoutMs = 90_000L;

    /** 心跳检查周期（毫秒）：看门狗轮询间隔，与超时阈值同量级即可 */
    private long heartbeatCheckIntervalMs = 15_000L;
}
