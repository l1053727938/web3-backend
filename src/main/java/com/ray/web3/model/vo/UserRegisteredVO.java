package com.ray.web3.model.vo;

/**
 * 注册结果（第 3 周）：用户与充值地址一次返回，前端可直接展示收款地址
 *
 * @param userId         用户 ID
 * @param username       登录名
 * @param depositAddress 该用户在当前链上的专属充值地址（0x 前缀，小写）
 */
public record UserRegisteredVO(long userId, String username, String depositAddress) {
}
