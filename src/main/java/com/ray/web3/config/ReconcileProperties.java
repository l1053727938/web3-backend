package com.ray.web3.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对账配置（第 5 周 Day 3-4）
 * <p>
 * 只放“判定规则”相关的静态配置：白名单与当前 decimals 覆盖表。
 * 窗口、分片、限流等运行参数在第 5 周 Day 5-6 再补。
 */
@Data
@ConfigurationProperties(prefix = "web3.reconcile")
public class ReconcileProperties {

    /** 是否启用每日对账（默认关闭，联调时通过接口手动触发） */
    private boolean enabled = false;

    /** 每日对账 cron（默认次日 01:30 跑前一天） */
    private String cron = "0 30 1 * * *";

    /** 按 user_id 哈希分片数（N=8） */
    private int shards = 8;

    /** 每个分片的线程数（core=2） */
    private int shardThreads = 2;

    /** 全局令牌桶限速（QPS）：所有分片共享，防一次性打爆从库 */
    private int qps = 50;

    /**
     * 代币白名单（合约地址，大小写不敏感）：
     * 不在白名单的到账合约归“待定性（未知代币）”，不进入长短款。
     * 留空时回退到 {@code web3.deposit.token-symbols} 的 key 集合。
     */
    private List<String> tokenWhitelist = new ArrayList<>();

    /**
     * 当前 decimals 覆盖表（合约 → decimals），用于构造/演示精度差：
     * 历史入账用 {@code deposit_record.decimals} 快照，若“当前值”与快照不同，
     * 且按当前值重算的金额正好等于账本金额，则判定为 PRECISION 而不是遗漏。
     * 未配置的合约回退到 {@link com.ray.web3.service.TokenMetadataService}。
     */
    private Map<String, Integer> currentDecimals = new LinkedHashMap<>();
}
