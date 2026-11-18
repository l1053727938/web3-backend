package com.ray.web3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ray.web3.model.entity.DepositWallet;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 充值地址配置 Mapper（REQ-BIZ-04）
 * <p>
 * 路由侧只做"全量启用地址"的批量读取（由 DepositWalletService 缓存后内存匹配），
 * 不给每个事件打一条 SELECT——实时链路事件密度高，逐条查询会把落库线程拖慢
 */
public interface DepositWalletMapper extends BaseMapper<DepositWallet> {

    /**
     * 该链全部启用的充值地址（热钱包 + 用户地址），供缓存加载
     */
    @Select("""
            SELECT * FROM deposit_wallet
            WHERE chain_id = #{chainId} AND status = 1
            """)
    List<DepositWallet> selectActiveByChain(@Param("chainId") Long chainId);

    /**
     * 查用户在某条链上的专属充值地址（{@code uk_chain_user} 保证最多一行）
     */
    @Select("""
            SELECT * FROM deposit_wallet
            WHERE chain_id = #{chainId} AND wallet_type = 'USER' AND user_id = #{userId}
            LIMIT 1
            """)
    DepositWallet selectUserWallet(@Param("chainId") Long chainId, @Param("userId") String userId);

    /**
     * 按收款地址批量反查 USER 地址的归属用户：路由写 Outbox 前校正 userId。
     * <p>
     * 只返回启用中的 USER 行；HOT 是平台归集地址，没有归属用户，不返回。
     */
    @Select("""
            <script>
            SELECT * FROM deposit_wallet
            WHERE chain_id = #{chainId}
              AND status = 1
              AND wallet_type = 'USER'
              AND address IN
              <foreach collection="addresses" item="address" open="(" separator="," close=")">
                #{address}
              </foreach>
            </script>
            """)
    List<DepositWallet> selectUserWalletsByAddresses(@Param("chainId") Long chainId,
                                                     @Param("addresses") Collection<String> addresses);
}
