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
}
