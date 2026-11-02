package com.ray.web3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ray.web3.model.entity.WalletNonce;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigInteger;

/**
 * 钱包 Nonce 分配游标 Mapper（第 3 周：写与发交易）
 * <p>
 * 只有一个自定义读法：{@link #selectForUpdate} 行锁读取——Nonce 分配的正确性完全建立在
 * “锁定 → 读值 → +1 写回”必须同事务原子这件事上，普通 SELECT 读后别的事务可能已推进游标，
 * 两个提币请求拿到同一个 Nonce，后广播的一笔覆盖前一笔（丢单）
 */
public interface WalletNonceMapper extends BaseMapper<WalletNonce> {

    /**
     * 事务内行锁读取当前待分配 Nonce；行不存在返回 null（由服务层先初始化再重取）
     * <p>
     * 必须在 {@code @Transactional} 方法内调用，否则自动提交模式下锁随语句结束立即释放，等于没锁
     */
    @Select("""
            SELECT next_nonce FROM wallet_nonce
            WHERE chain_id = #{chainId} AND address = #{address}
            FOR UPDATE
            """)
    BigInteger selectForUpdate(@Param("chainId") Long chainId, @Param("address") String address);

    /**
     * 普通读（无锁）：观测/预览当前待分配 Nonce，不用于分配路径
     */
    @Select("""
            SELECT next_nonce FROM wallet_nonce
            WHERE chain_id = #{chainId} AND address = #{address}
            """)
    BigInteger selectCurrent(@Param("chainId") Long chainId, @Param("address") String address);

    /**
     * 幂等初始化游标行：INSERT IGNORE 命中唯一键 (chain_id, address) 时静默跳过，
     * 并发首建两个线程只有一个真正插入（初始值由服务层用链上 pending Nonce 种子）
     */
    @Insert("""
            INSERT IGNORE INTO wallet_nonce (chain_id, address, next_nonce)
            VALUES (#{chainId}, #{address}, #{initNonce})
            """)
    int insertIgnore(@Param("chainId") Long chainId, @Param("address") String address,
                     @Param("initNonce") BigInteger initNonce);

    /**
     * 推进/校准游标到目标值：分配路径在持有行锁的事务内调用；
     * 校准路径仅向上推（GREATEST 口径由服务层保证，这里不做条件保护以减少 SQL 分支）
     */
    @Update("""
            UPDATE wallet_nonce SET next_nonce = #{target}
            WHERE chain_id = #{chainId} AND address = #{address}
            """)
    int updateNext(@Param("chainId") Long chainId, @Param("address") String address,
                   @Param("target") BigInteger target);
}
