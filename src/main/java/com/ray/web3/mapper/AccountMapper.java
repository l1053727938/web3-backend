package com.ray.web3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ray.web3.model.entity.Account;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 账户余额 Mapper（第 2 周 U1-b）
 * <p>
 * 余额写入只有一个入口 {@link #updateBalance}：带 {@code version} 的 CAS 更新。
 * 绝不允许出现 {@code UPDATE account SET available = available - ?} 这种盲减——
 * 盲减在并发下会把余额扣成负数，且没有版本号可供对账追溯。
 */
public interface AccountMapper extends BaseMapper<Account> {

    /**
     * 事务内行锁读取账户（含 version）；不存在返回 null
     * <p>
     * 记账时先按 account_id 升序逐行加锁，同一批账户跨事务同序加锁，避免两个转账事务交叉更新时死锁。
     * 必须在 {@code @Transactional} 方法内调用，否则锁在语句结束即释放。
     */
    @Select("""
            SELECT id, user_id, account_type, currency, available, frozen, version
            FROM `account`
            WHERE id = #{id}
            FOR UPDATE
            """)
    Account selectByIdForUpdate(@Param("id") Long id);

    /**
     * 乐观锁回写余额：命中 {@code (id, version)} 才算成功，返回 0 表示期间被并发事务改过，
     * 调用方必须抛异常回滚整组分录，而不是重读后重试（重试会让 balance_after 失真）。
     */
    @Update("""
            UPDATE `account`
            SET available = #{available},
                frozen    = #{frozen},
                version   = version + 1
            WHERE id = #{id} AND version = #{version}
            """)
    int updateBalance(@Param("id") Long id,
                      @Param("available") BigDecimal available,
                      @Param("frozen") BigDecimal frozen,
                      @Param("version") Long version);
}
