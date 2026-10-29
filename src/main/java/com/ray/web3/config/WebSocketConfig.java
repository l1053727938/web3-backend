package com.ray.web3.config;

import com.ray.web3.util.ProxyTunnel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.websocket.WebSocketClient;
import org.web3j.protocol.websocket.WebSocketService;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.URI;

/**
 * WebSocket 长连接配置（web3.websocket.enabled=true 时生效）
 * <p>
 * 与 Web3jConfig 的 HTTP 实例并存：wsWeb3j 专用于 newHeads / logs 实时订阅，
 * 注入时需 @Qualifier("wsWeb3j") 区分
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "web3.websocket.enabled", havingValue = "true")
@EnableConfigurationProperties(WebSocketProperties.class)
public class WebSocketConfig {

    /** 代理建隧道 + TLS 握手的超时上限 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /**
     * WebSocketService：构造传入 ws/wss 地址并显式 connect()；
     * includeRawValues=true 时事件参数为原始 hex（与 HTTP eth_getLogs 返回结构一致）
     * <p>
     * 底层 Java-WebSocket 默认 Proxy.NO_PROXY，既不读系统代理也不读 -Dhttp.proxyHost，
     * 本机需代理访问主网时 wss 会直接 ConnectException；这里用 CONNECT 隧道自行代理
     */
    @Bean
    @SuppressWarnings("deprecation") // Java-WebSocket#setSocket 已废弃，但它是复用已有隧道的唯一入口
    public WebSocketService webSocketService(WebSocketProperties properties) throws Exception {
        String url = properties.getUrl();
        log.info("正在建立 WebSocket 连接: {}", url);
        WebSocketClient client = new WebSocketClient(URI.create(url));
        SSLSocket tunnel = openTunnelIfNecessary(url, properties);
        if (tunnel != null) {
            // 必须在 connect() 之前注入；Java-WebSocket 对已建好的 SSLSocket 不再重复包装 TLS
            // 官方建议改用 setSocketFactory，但 factory 分支会对目标地址重新 connect 从而绕过隧道，
            // 只有预置已连接 socket 这条路能复用隧道
            client.setSocket(tunnel);
        }
        WebSocketService service = new WebSocketService(client, true);
        service.connect();
        log.info("WebSocket 连接成功");
        return service;
    }

    /**
     * 需要代理且目标是 wss 端点时建立 TLS 隧道；ws://（本地节点如 Anvil）或未配置代理时返回 null 直连
     */
    private SSLSocket openTunnelIfNecessary(String url, WebSocketProperties properties) throws IOException {
        String proxyHost = properties.getProxyHost();
        Integer proxyPort = properties.getProxyPort();
        boolean proxied = StringUtils.hasText(proxyHost) && proxyPort != null && proxyPort > 0;
        URI uri = URI.create(url);
        if (!proxied || !"wss".equalsIgnoreCase(uri.getScheme())) {
            if (!proxied && "wss".equalsIgnoreCase(uri.getScheme())) {
                log.warn("未配置 web3.websocket.proxy-host/proxy-port，wss 将直连（本机靠代理上外网时可能失败）");
            }
            return null;
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        log.info("WebSocket 经由 HTTP 代理建立 CONNECT 隧道: {}:{} -> {}:{}",
                proxyHost, proxyPort, uri.getHost(), port);
        return ProxyTunnel.open(proxyHost, proxyPort, uri.getHost(), port, CONNECT_TIMEOUT_MS);
    }

    /**
     * 基于 WebSocket 的 Web3j 实例：仅 shutdown 调度资源，
     * 底层连接由 ChainEventListener 按序（先退订、后 close）释放
     */
    @Bean(destroyMethod = "shutdown")
    public Web3j wsWeb3j(WebSocketService webSocketService) {
        return Web3j.build(webSocketService);
    }
}
