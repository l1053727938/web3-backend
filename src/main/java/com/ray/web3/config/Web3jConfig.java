package com.ray.web3.config;

import com.ray.web3.rpc.RpcProviderManager;
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
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties({InfuraProperties.class, BackfillProperties.class})
public class Web3jConfig {

    /**
     * HTTP JSON-RPC 通道：抽成独立 Bean，除供 {@link Web3j} 构建外，
     * 也让需要发起「web3j 未封装的自定义 RPC」（如 debug_traceTransaction）的服务直接复用同一连接与代理配置。
     * <p>
     * 第 4 周 Day 5-6：返回 {@link RpcProviderManager}（实现 {@link Web3jService}），
     * 在传输层做“主节点 + 备用节点”切换；业务侧仍注入普通 {@link Web3j}，无感知。
     * Bean 名固定 web3jService，注入点按名匹配可在与 wsWeb3j 的 WebSocketService（同为 Web3jService）共存时消歧。
     */
    @Bean(destroyMethod = "close")
    public RpcProviderManager web3jService(InfuraProperties properties) {
        List<Web3jService> providers = new ArrayList<>();
        providers.add(buildHttpService(properties.getHttpUrl(), properties));

        InfuraProperties.Failover failover = properties.getFailover();
        if (failover.isEnabled() && failover.getBackupUrls() != null) {
            for (String backupUrl : failover.getBackupUrls()) {
                // 空占位（环境变量未设置时常见）直接跳过，避免创建一个空 URL 的 HttpService
                if (StringUtils.hasText(backupUrl)) {
                    providers.add(buildHttpService(backupUrl, properties));
                }
            }
        }
        return new RpcProviderManager(providers, failover.getFailureThreshold());
    }

    private static Web3jService buildHttpService(String url, InfuraProperties properties) {
        String proxyHost = properties.getProxyHost();
        Integer proxyPort = properties.getProxyPort();
        if (StringUtils.hasText(proxyHost) && proxyPort != null && proxyPort > 0) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            OkHttpClient httpClient = new OkHttpClient.Builder().proxy(proxy).build();
            return new HttpService(url, httpClient);
        }
        return new HttpService(url);
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
