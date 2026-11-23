package com.ray.web3.scanner;

import com.ray.web3.rpc.RpcProviderManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RPC 主节点半开探活（第 4 周 Day 5-6 余量）
 * <p>
 * 仅 {@code web3.infura.failover.enabled=true} 时加载；每 30s 用 {@code eth_blockNumber}
 * 试一次主节点，成功后由 {@link RpcProviderManager} 切回主节点。
 * 与业务请求独立：探活失败只记日志，不影响正在走备用节点的调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "web3.infura.failover.enabled", havingValue = "true")
public class RpcProviderProbeScheduler {

    private final RpcProviderManager rpcProviderManager;

    @Scheduled(fixedDelayString = "${web3.infura.failover.probe-interval-ms:30000}")
    public void probePrimary() {
        try {
            rpcProviderManager.probePrimary();
        } catch (Exception e) {
            log.warn("[rpc-failover] 主节点探活异常: {}", e.getMessage());
        }
    }
}