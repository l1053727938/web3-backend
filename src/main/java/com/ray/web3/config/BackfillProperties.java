package com.ray.web3.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 历史日志补拉（REQ-SYNC-02）配置：对应 web3.backfill.*
 * <p>
 * 作为监听器启动时的“状态补齐”机制，与 BlockScanner 的实时扫描互补：
 * 补拉以 block_sync_state 中的游标为断点，按页推进，确保不漏单
 */
@Data
@ConfigurationProperties(prefix = "web3.backfill")
public class BackfillProperties {

    /** 补拉服务总开关；关闭时 HistoryBackfillService Bean 不注册，相关端点返回未启用提示 */
    private boolean enabled = true;

    /** 单页区块跨度：每次 eth_getLogs 请求覆盖的最大区块数（节点单次结果上限被打穿时由 EventLogService 自动缩跨度续拉） */
    private int chunkSize = 1000;

    /** 单次调用最多推进的区块数，0=不限；设为正数可把大区间拆成多次调用，下次从游标自动续拉 */
    private long maxBlocksPerRun = 0L;

    /** 游标读是否优先走 Redis（关闭后每次直读 MySQL；Redis 不可用时也会自动降级回源） */
    private boolean cursorCacheEnabled = true;
}
