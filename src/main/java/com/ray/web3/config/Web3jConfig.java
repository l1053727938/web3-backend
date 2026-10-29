package com.ray.web3.config;

import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.net.InetSocketAddress;
import java.net.Proxy;

@Configuration
@EnableConfigurationProperties({InfuraProperties.class, BackfillProperties.class})
public class Web3jConfig {

    /**
     * Web3j 客户端：通过 Infura HTTP RPC 接入
     * 连接在发起请求时才建立，启动阶段无 projectId 也不会阻塞应用启动
     * 若配置了 web3.infura.proxy-host/proxy-port，则通过该 HTTP 代理访问 RPC
     * （解决本机开了系统代理、但 Java 默认不走代理导致主网超时的问题）
     */
    @Bean(destroyMethod = "shutdown")
    public Web3j web3j(InfuraProperties properties) {
        String proxyHost = properties.getProxyHost();
        Integer proxyPort = properties.getProxyPort();
        if (StringUtils.hasText(proxyHost) && proxyPort != null && proxyPort > 0) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            OkHttpClient httpClient = new OkHttpClient.Builder().proxy(proxy).build();
            return Web3j.build(new HttpService(properties.getHttpUrl(), httpClient));
        }
        return Web3j.build(new HttpService(properties.getHttpUrl()));
    }
}
