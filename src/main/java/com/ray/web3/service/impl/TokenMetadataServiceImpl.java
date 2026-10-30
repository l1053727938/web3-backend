package com.ray.web3.service.impl;

import com.ray.web3.service.TokenMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代币精度获取：链上 eth_call decimals() 为主，内置常见精度表回退，兜底 18
 * <p>
 * decimals() 是只读调用、不耗 Gas；结果按合约地址（小写）缓存，避免整区间分页解码时反复请求同一精度
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenMetadataServiceImpl implements TokenMetadataService {

    /** decimals() 输出蓝图：返回 uint8（匿名子类捕获泛型，与 EventConsts 写法一致） */
    private static final List<TypeReference<?>> DECIMALS_OUTPUT =
            Collections.singletonList(new TypeReference<Uint8>() {
            });

    /** decimals() 无入参、返回 uint8，固定蓝图 */
    private static final Function DECIMALS_FUNCTION = new Function(
            "decimals", Collections.emptyList(), DECIMALS_OUTPUT);

    /** 精度不可获取时的兜底值（ERC-20 约定默认 18） */
    private static final int DEFAULT_DECIMALS = 18;

    /** 已知精度回退表：链上调用失败（合约未实现 decimals()/节点异常）时按地址命中，键为小写地址 */
    private static final Map<String, Integer> FALLBACK_DECIMALS = Map.of(
            "0xdac17f958d2ee523a2206206994597c13d831ec7", 6,   // Tether USDT
            "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", 6,   // USDC
            "0x2260fac5e5542a773aa44fbcfedf7c193bc2c599", 8,   // WBTC
            "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", 18,  // WETH
            "0x0fD04a68d3c3A692d6Fa30384D1A87Ef93554eE6".toLowerCase(), 18);

    /** 精度缓存：同一合约整区间只取一次 */
    private final Map<String, Integer> cache = new ConcurrentHashMap<>();

    private final Web3j web3j;

    @Override
    public int decimals(String contractAddress) {
        if (contractAddress == null) {
            return DEFAULT_DECIMALS;
        }
        String key = contractAddress.toLowerCase();
        return cache.computeIfAbsent(key, this::fetchDecimals);
    }

    /**
     * 链上取精度：eth_call decimals() → 解码 uint8；任何失败（节点错误/revert/空返回/解码异常）都回退精度表
     */
    private int fetchDecimals(String lowerAddress) {
        try {
            String data = FunctionEncoder.encode(DECIMALS_FUNCTION);
            EthCall ethCall = web3j.ethCall(
                    Transaction.createEthCallTransaction(null, lowerAddress, data),
                    DefaultBlockParameterName.LATEST).send();
            if (ethCall.hasError() || ethCall.isReverted()) {
                return fallback(lowerAddress, ethCall.getError() != null
                        ? ethCall.getError().getMessage() : "decimals() 调用被回退");
            }
            List<Type> result = FunctionReturnDecoder.decode(ethCall.getValue(), DECIMALS_FUNCTION.getOutputParameters());
            if (result.isEmpty()) {
                return fallback(lowerAddress, "decimals() 返回空");
            }
            return ((Uint8) result.getFirst()).getValue().intValueExact();
        } catch (Exception e) {
            return fallback(lowerAddress, e.getMessage());
        }
    }

    private int fallback(String lowerAddress, String reason) {
        Integer known = FALLBACK_DECIMALS.get(lowerAddress);
        if (known != null) {
            log.info("decimals() 不可用({})，命中回退表 contract={} decimals={}", reason, lowerAddress, known);
            return known;
        }
        log.warn("decimals() 不可用({})且无回退记录，兜底 {} contract={}", reason, DEFAULT_DECIMALS, lowerAddress);
        return DEFAULT_DECIMALS;
    }
}
