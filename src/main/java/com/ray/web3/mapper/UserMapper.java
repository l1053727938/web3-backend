package com.ray.web3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ray.web3.model.entity.User;

/**
 * 用户主表 Mapper（第 3 周）
 * <p>
 * 业务表走业务连接池（包在 {@code com.ray.web3.mapper} 下），与账本池隔离。
 * {@code uk_username} 是注册幂等的最终防线：并发注册同名用户时，数据库只放过一个。
 */
public interface UserMapper extends BaseMapper<User> {
}
