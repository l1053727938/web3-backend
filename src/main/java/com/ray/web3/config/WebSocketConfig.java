package com.ray.web3.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.websocket.WebSocketService;

/**
 * WebSocket 长连接配置（web3.websocket.enabled=true 时生效）
 * <p>
 * 与 Web3jConfig 的 HTTP 实例并存：wsWeb3j 专用于实时订阅，注入时需 @Qualifier("wsWeb3j") 区分。
 * 这里的 webSocketService/wsWeb3j 是共享单例（供 ChainEventListener 等使用）；
 * 模块三的实时引擎会另行用 {@link WebSocketServiceFactory} 建立可重连的独立连接，二者互不影响。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "web3.websocket.enabled", havingValue = "true")
@EnableConfigurationProperties(WebSocketProperties.class)
public class WebSocketConfig {

    /**
     * 共享 WebSocketService：构造隧道+客户端后显式 connect()；includeRawValues=true 时事件参数为原始 hex。
     * 具体构造（代理隧道等）收敛到 {@link WebSocketServiceFactory}
     */
    @Bean
    public WebSocketService webSocketService(WebSocketProperties properties,
                                             WebSocketServiceFactory factory) throws Exception {
        log.info("正在建立 WebSocket 连接: {}", properties.getUrl());
        WebSocketService service = factory.createService(properties);
        service.connect();
        log.info("WebSocket 连接成功");
        return service;
    }

    /**
     * 基于 WebSocket 的 Web3j 实例：仅 shutdown 调度资源，
     * 底层连接由使用方按序（先退订、后 close）释放
     */
    @Bean(destroyMethod = "shutdown")
    public Web3j wsWeb3j(WebSocketService webSocketService) {
        return Web3j.build(webSocketService);
    }
}
