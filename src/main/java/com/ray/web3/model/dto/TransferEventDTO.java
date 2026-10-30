package com.ray.web3.model.dto;

import java.math.BigInteger;

/**
 * 解码后的 ERC-20 Transfer 事件业务对象（模块一解析器产出，模块二补拉落库消费）
 * <p>
 * displayValue 已按代币 decimals() 动态换算，与 Etherscan 展示一致；
 * rawValue 保留最小单位原始整数，落库时两列分别存字符串（uint256 可达 78 位，DECIMAL 容不下）
 *
 * @param contract       代币合约地址（统一小写，避免大小写导致唯一键分裂）
 * @param blockNumber    区块高度
 * @param transactionHash 交易哈希
 * @param logIndex       日志在区块内的序号
 * @param from           转出地址
 * @param to             转入地址
 * @param rawValue       原始金额（最小单位）
 * @param displayValue   按精度换算后的十进制字符串（保留尾零）
 * @param decimals       换算所用代币精度
 */
public record TransferEventDTO(
        String contract,
        BigInteger blockNumber,
        String transactionHash,
        BigInteger logIndex,
        String from,
        String to,
        BigInteger rawValue,
        String displayValue,
        int decimals) {
}
