package com.ray.web3.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 充值业务路由配置（REQ-BIZ-04 模块四）
 * <p>
 * 热钱包匹配的地址来源有两个：配置中心（{@code web3.deposit.hot-wallets}，适合少量平台归集地址）
 * 与数据库 deposit_wallet 表（适合用户专属充值地址这类量大/频繁变动的场景），两者取并集
 */
@Data
@ConfigurationProperties(prefix = "web3.deposit")
public class DepositProperties {

    /** 是否启用充值路由与落库（关闭后实时/补拉链路只写流水表，不产生充值记录） */
    private boolean enabled = true;

    /** Redis 下游通知频道：入账成功后 PUBLISH 该频道，账务系统订阅 */
    private String depositChannel = "deposit_channel";

    /**
     * 配置侧热钱包：key = 地址（大小写不敏感，内部按小写匹配），value = 币种符号（留空则不限币种，任意代币到账均计充值）
     */
    private Map<String, String> hotWallets = new LinkedHashMap<>();

    /**
     * 合约 → 币种符号兜底表（key 为合约地址，大小写不敏感，内部统一小写）。
     * <p>
     * 用于钱包配置本身没有 currency 的场景，典型是「不限合约」的配置中心热钱包：
     * 保存钱包时不知道会收到哪个币，只有事件到达时才能按 contract 反查符号。
     * 命中优先级：{@code deposit_wallet.currency} → 本表 → 空（下游会拒绝入账）。
     */
    private Map<String, String> tokenSymbols = new LinkedHashMap<>();

    /** 充值地址缓存刷新间隔（毫秒）：到期后下一次路由触发回源，避免每个事件打一次 DB */
    private long walletCacheRefreshMs = 30_000L;

    /** 确认阈值（区块数）：链上最新高度 - 入账高度 ≥ 该值时由确认任务置为已入账；0=不做确认推进 */
    private long confirmThreshold = 12L;

    // ===== 第 5 周 Day 5-6：重组回滚检测 =====

    /** 是否启用重组检测（默认关闭；本地 Reorg 演练时打开） */
    private boolean reorgEnabled = false;

    private long reorgIntervalMs = 60000L;

    /** 回看区块深度：只检查最近 N 个块内已确认的充值 */
    private int reorgLookbackBlocks = 200;

    /** 单轮最多检查条数 */
    private int reorgBatchSize = 100;

    /**
     * 合约地址大小写不敏感：Spring 绑定后统一转小写，避免配置里写成混合大小写导致反查漏命中。
     * <p>
     * 自定义 setter 覆盖 Lombok 生成的版本，保证任何来源（yml / 配置中心）写入都经过归一化。
     */
    public void setTokenSymbols(Map<String, String> tokenSymbols) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (tokenSymbols != null) {
            tokenSymbols.forEach((contract, symbol) -> {
                if (contract != null && !contract.isBlank()) {
                    normalized.put(contract.trim().toLowerCase(), symbol);
                }
            });
        }
        this.tokenSymbols = normalized;
    }
}
