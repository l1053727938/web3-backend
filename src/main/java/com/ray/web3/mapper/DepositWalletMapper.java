package com.ray.web3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ray.web3.model.entity.DepositWallet;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
