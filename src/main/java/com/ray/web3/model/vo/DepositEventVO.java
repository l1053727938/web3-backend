package com.ray.web3.model.vo;

import java.time.LocalDateTime;

/**
 * 充值入账下游通知消息（REQ-BIZ-04，Redis {@code PUBLISH deposit_channel {json}} 的载荷）
 * <p>
 * 金额一律用十进制字符串：uint256 最多 78 位，超出 long/BigDecimal 常规展示范围，
 * 下游按 decimals 自行换算可无损还原 raw 值
 *
 * @param depositNo       充值单号（下游幂等键，重复消息按此去重）
 * @param chainId         链 ID
 * @param contractAddress 代币合约地址（小写）
 * @param currency        币种符号（未配置时为空）
 * @param transactionHash 交易哈希
 * @param logIndex        日志序号
 * @param blockNumber     入账区块高度
 * @param fromAddress     转出地址
 * @param toAddress       命中的平台充值地址
 * @param walletType      地址类型：HOT=平台热钱包，USER=用户充值地址
 * @param userId          归属用户（HOT 时为空）
 * @param amount          到账金额（已按代币精度换算）
 * @param rawValue        原始金额（最小单位）
 * @param decimals        换算所用精度
 * @param status          充值状态（新建固定为 PENDING_CONFIRM=待确认）
 * @param confirmRequired 确认阈值（区块数），下游可据此判断何时视为最终确定
 * @param eventSource     事件来源：STREAM=实时订阅，BACKFILL=历史补拉
 * @param discoveredAt    链上发现时间（入库时间）
 */
public record DepositEventVO(
        String depositNo,
        Long chainId,
        String contractAddress,
        String currency,
        String transactionHash,
        Long logIndex,
        Long blockNumber,
        String fromAddress,
        String toAddress,
        String walletType,
        String userId,
        String amount,
        String rawValue,
        Integer decimals,
        String status,
        Long confirmRequired,
        String eventSource,
        LocalDateTime discoveredAt) {
}
