package com.ray.web3.model.vo;

/**
 * 用户充值地址（第 3 周）
 *
 * @param userId  用户 ID
 * @param chainId 链 ID（地址按链分配，切换链需要重新查询）
 * @param address 充值地址（0x 前缀，小写）
 */
public record DepositAddressVO(long userId, long chainId, String address) {
}
