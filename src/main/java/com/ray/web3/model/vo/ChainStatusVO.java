package com.ray.web3.model.vo;

import java.math.BigInteger;

/**
 * 链上状态概览
 */
public record ChainStatusVO(String clientVersion,
                            Long chainId,
                            BigInteger latestBlock,
                            BigInteger gasPrice) {
}
