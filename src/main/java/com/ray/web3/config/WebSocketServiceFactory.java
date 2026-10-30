package com.ray.web3.config;

import com.ray.web3.util.ProxyTunnel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.websocket.WebSocketClient;
import org.web3j.protocol.websocket.WebSocketService;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * WebSocketService / Web3j 工厂：把"代理隧道 + Java-WebSocket 客户端 + web3j 服务"的构造收敛到一处，
 * 供 {@link WebSocketConfig} 与模块三实时订阅引擎复用（同一份隧道逻辑不漂移）。
 * <p>
 * 关键点：底层 Java-WebSocket 不认系统/JVM 代理，wss 出网必须经 {@link ProxyTunnel} 手工 CONNECT 隧道；
 * 且隧道 socket 一旦关闭无法被 reconnectBlocking 复用，因此"重连"只能重建整套 client/service/隧道——
 * 这正是工厂存在的意义：重连时重新 buildService 得到一个全新可用连接。
 */
@Slf4j
@Component
public class WebSocketServiceFactory {

    /** 代理建隧道 + TLS 握手的超时上限 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /**
     * 构造一个<b>未连接</b>的 WebSocketService（含必要的代理隧道 socket 注入）。
     * 由调用方决定何时 connect()/重连，工厂不负责连接生命周期。
     *
     * @param properties wss 地址与代理配置
     * @return includeRawValues=true 的 WebSocketService（事件参数为原始 hex）
     */
    @SuppressWarnings("deprecation") // Java-WebSocket#setSocket 已废弃，但它是复用已有隧道的唯一入口
    public WebSocketService createService(WebSocketProperties properties) throws Exception {
        String url = properties.getUrl();
        WebSocketClient client = new WebSocketClient(URI.create(url));
        SSLSocket tunnel = openTunnelIfNecessary(url, properties);
        if (tunnel != null) {
            // 必须在 connect() 之前注入；官方建议的 setSocketFactory 会对目标地址重新 connect 从而绕过隧道，
            // 只有预置已连接 socket 这条路能复用隧道
            client.setSocket(tunnel);
        }
        return new WebSocketService(client, true);
    }

    /**
     * 基于给定 service 构建 Web3j，并按 pollingIntervalMs 定制 Flowable 轮询间隔（ethLogFlowable 生效）。
     * 返回的 Web3j 由调用方 shutdown()（连带关闭此处创建的调度线程池）。
     */
    public Web3j createWeb3j(WebSocketService service, long pollingIntervalMs) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-rx-poller");
            t.setDaemon(true);
            return t;
        });
        return Web3j.build(service, pollingIntervalMs, executor);
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
}
