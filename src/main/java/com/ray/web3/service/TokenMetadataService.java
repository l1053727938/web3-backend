package com.ray.web3.service;

/**
 * 代币元数据服务：为事件解码提供精度（decimals）
 * <p>
 * 精度决定 rawValue → displayValue 的换算，模块一解码器与模块二补拉落库都依赖它
 */
public interface TokenMetadataService {

    /**
     * 获取代币精度：优先链上 eth_call decimals()，失败时回退内置精度表，最终兜底 18
     * <p>
     * 结果按合约地址缓存，同一合约重复拉取（如整区间分页解码）只发一次链上请求
     *
     * @param contractAddress 代币合约地址（0x 开头，大小写不敏感）
     * @return 小数位数（ETH/WRAP 18、USDT/USDC 6 等）
     */
    int decimals(String contractAddress);
}
