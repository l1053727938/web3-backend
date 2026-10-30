package com.ray.web3.consts;

import java.util.regex.Pattern;

/**
 * 链上数据格式校验正则常量：收拢原先散落在各 Service 里重复定义的地址/交易哈希匹配式
 * <p>
 * 地址口径刻意分成两套，不是冗余：
 * 1. {@link #ADDRESS_PATTERN} 要求小写前缀 0x，给<b>直接透传给 RPC 节点</b>的入参用
 *    （节点会把 0X 前缀当非法十六进制拒掉，本地先拦比白耗一次 RPC 调用划算）；
 * 2. {@link #LOOSE_ADDRESS_PATTERN} 前缀 0x/0X 都接受，给<b>先归一小写再落库/匹配</b>的录入场景用
 *    （运维手写配置常把前缀写成 0X，归一后与节点口径一致，没有拒绝的理由）
 */
public final class PatternConsts {

    /** 账户/合约地址：0x + 40 位 hex（严格小写前缀，RPC 入参口径） */
    public static final Pattern ADDRESS_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    /** 账户/合约地址：前缀 0x/0X 均可，校验方需自行 toLowerCase 归一后再使用 */
    public static final Pattern LOOSE_ADDRESS_PATTERN = Pattern.compile("^0[xX][0-9a-fA-F]{40}$");

    /** 交易哈希：0x + 64 位 hex */
    public static final Pattern TX_HASH_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{64}$");

    private PatternConsts() {
    }

    /** 是否为可直接用于 RPC 的地址（null 视为非法） */
    public static boolean isAddress(String address) {
        return address != null && ADDRESS_PATTERN.matcher(address).matches();
    }

    /** 是否为可归一小写后入库/匹配的地址（null 视为非法） */
    public static boolean isLooseAddress(String address) {
        return address != null && LOOSE_ADDRESS_PATTERN.matcher(address).matches();
    }

    /** 是否为合法交易哈希（null 视为非法） */
    public static boolean isTxHash(String txHash) {
        return txHash != null && TX_HASH_PATTERN.matcher(txHash).matches();
    }
}
