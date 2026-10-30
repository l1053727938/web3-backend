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

    /** 充值地址缓存刷新间隔（毫秒）：到期后下一次路由触发回源，避免每个事件打一次 DB */
    private long walletCacheRefreshMs = 30_000L;

    /** 确认阈值（区块数）：链上最新高度 - 入账高度 ≥ 该值时由确认任务置为已入账；0=不做确认推进 */
    private long confirmThreshold = 12L;
}
