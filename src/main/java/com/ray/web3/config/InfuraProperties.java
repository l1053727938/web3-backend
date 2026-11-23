package com.ray.web3.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Infura RPC 接入配置
 */
@Data
@ConfigurationProperties(prefix = "web3.infura")
public class InfuraProperties {

    /** Infura Project ID */
    private String projectId;

    /** HTTP RPC 地址，如 https://sepolia.infura.io/v3/{projectId} */
    private String httpUrl;

    /** WebSocket RPC 地址，如 wss://sepolia.infura.io/ws/v3/{projectId} */
    private String wsUrl;

    /** 链 ID，Sepolia = 11155111 */
    private Long chainId;

    /** HTTP RPC 代理主机；为空则直连（本机需走系统代理访问外网时填写，如 127.0.0.1） */
    private String proxyHost;

    /** HTTP RPC 代理端口，配合 proxyHost 使用（如 7890） */
    private Integer proxyPort;

    /** eth_getLogs 分页拉取时的单段最大区块跨度（Infura 等节点对跨度/日志数有限制） */
    private int logChunkSize = 5;

    /** RPC 主备容灾配置（第 4 周 Day 5-6 余量） */
    private Failover failover = new Failover();

    @Data
    public static class Failover {

        /** 是否启用主备切换；关闭时只使用 http-url */
        private boolean enabled = false;

        /** 备用 RPC 地址列表；主节点仍是 http-url */
        private List<String> backupUrls = new ArrayList<>();

        /** 连续失败达到该次数后切换；默认 1 = 429/超时立即切 */
        private int failureThreshold = 1;

        /** 半开探活间隔（毫秒）：周期性用 eth_blockNumber 试主节点 */
        private long probeIntervalMs = 30_000L;
    }
}
