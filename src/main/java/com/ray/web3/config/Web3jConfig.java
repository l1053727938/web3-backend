package com.ray.web3.config;

import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.http.HttpService;

import java.net.InetSocketAddress;
import java.net.Proxy;

@Configuration
@EnableConfigurationProperties({InfuraProperties.class, BackfillProperties.class})
public class Web3jConfig {

    /**
     * HTTP JSON-RPC 通道：抽成独立 Bean，除供 {@link Web3j} 构建外，
     * 也让需要发起「web3j 未封装的自定义 RPC」（如 debug_traceTransaction）的服务直接复用同一连接与代理配置。
     * Bean 名固定 web3jService，注入点按名匹配可在与 wsWeb3j 的 WebSocketService（同为 Web3jService）共存时消歧。
     */
    @Bean
    public Web3jService web3jService(InfuraProperties properties) {
        String proxyHost = properties.getProxyHost();
        Integer proxyPort = properties.getProxyPort();
        if (StringUtils.hasText(proxyHost) && proxyPort != null && proxyPort > 0) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            OkHttpClient httpClient = new OkHttpClient.Builder().proxy(proxy).build();
            return new HttpService(properties.getHttpUrl(), httpClient);
        }
        return new HttpService(properties.getHttpUrl());
    }

    /**
     * Web3j 客户端：基于 {@link #web3jService} 通过 Infura HTTP RPC 接入
     * 连接在发起请求时才建立，启动阶段无 projectId 也不会阻塞应用启动
     * destroyMethod = "shutdown" 保证退出时释放 OkHttp 连接池
     */
    @Bean(destroyMethod = "shutdown")
    public Web3j web3j(Web3jService web3jService) {
        return Web3j.build(web3jService);
    }
}
