package com.ray.web3.model.vo;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 地址余额（wei + ether）
 */
public record BalanceVO(String address, BigInteger wei, BigDecimal ether) {
}
