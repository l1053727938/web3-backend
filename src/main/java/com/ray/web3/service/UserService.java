package com.ray.web3.service;

/**
 * 用户业务（第 3 周）：注册与充值地址分配
 * <p>
 * 注册是一个幂等动作：同名用户重复提交返回同一个用户与同一个地址，
 * 不会产生第二个用户，也不会派生第二个地址。地址由 user_id 决定，天然可重放。
 */
public interface UserService {

    /**
     * 注册用户；已存在同名用户时直接返回原用户与原地址（幂等）
     *
     * @throws IllegalArgumentException 用户名为空或格式非法
     */
    Registered register(String username);

    /**
     * 查询用户在该链上的充值地址；用户不存在抛 {@link IllegalArgumentException}
     */
    String depositAddress(long userId);

    /**
     * @param userId         用户 ID
     * @param username       登录名
     * @param depositAddress 该用户在当前链上的专属充值地址
     */
    record Registered(long userId, String username, String depositAddress) {
    }
}
