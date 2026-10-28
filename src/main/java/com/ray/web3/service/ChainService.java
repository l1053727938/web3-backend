package com.ray.web3.service;

import com.ray.web3.model.vo.BalanceVO;
import com.ray.web3.model.vo.ChainStatusVO;

import java.math.BigInteger;

public interface ChainService {

    /** 链上状态：客户端版本、链ID、最新区块、Gas Price */
    ChainStatusVO status();

    /** 最新区块高度 */
    BigInteger latestBlockNumber();

    /** 地址余额 */
    BalanceVO getBalance(String address);
}
