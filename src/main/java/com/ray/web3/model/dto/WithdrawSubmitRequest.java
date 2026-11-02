package com.ray.web3.model.dto;

/**
 * ERC-20 提币提交请求体（第 3 周：写与发交易）
 * <p>
 * rawValue 用十进制字符串而非 JSON 数字：代币最小单位是 uint256（最长 78 位十进制），
 * 数字字面量会被 JS 端 Number 精度截断，字符串口径全链路无损（同充值侧 raw_value 规范）
 *
 * @param tokenContract 代币合约地址（0x 前缀，大小写不限，服务内归一小写）
 * @param recipient     提币目标地址
 * @param rawValue      提币金额（代币最小单位，十进制正整数字符串）
 */
public record WithdrawSubmitRequest(String tokenContract, String recipient, String rawValue) {
}
