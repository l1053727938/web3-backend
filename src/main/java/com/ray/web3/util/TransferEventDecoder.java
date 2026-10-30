package com.ray.web3.util;

import com.ray.web3.consts.EventConsts;
import com.ray.web3.model.dto.TransferEventDTO;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.EventValues;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ERC-20 Transfer 事件解码器（模块一产出，供 eth_getLogs 分页/单页拉取与实时监听共用）
 * <p>
 * topic[0] 由事件蓝图 {@link EventConsts#TRANSFER_EVENT} 经 EventEncoder 计算，是全项目唯一来源，
 * 过滤（buildFilter）与解码校验都取这一个值，避免两处条件漂移；非 Transfer 日志一律丢弃
 */
public final class TransferEventDecoder {

    private static final Event TRANSFER_EVENT = EventConsts.TRANSFER_EVENT;

    /** Transfer(address,address,uint256) 的 topic0 = keccak 签名，0x 前缀 32 字节 hex */
    private static final String TRANSFER_TOPIC = EventEncoder.encode(TRANSFER_EVENT);

    private TransferEventDecoder() {
    }

    /**
     * Transfer 事件 topic0：eth_getLogs 过滤与解码校验共用的单一来源
     */
    public static String transferEventTopic() {
        return TRANSFER_TOPIC;
    }

    /**
     * 按 Transfer 蓝图解码日志的 indexed/non-indexed 参数；非 Transfer（topic0 不匹配或 topics 不足）返回 empty
     */
    public static Optional<EventValues> extractEventValues(Log raw) {
        if (raw == null) {
            return Optional.empty();
        }
        List<String> topics = raw.getTopics();
        // Transfer 固定 4 项 topic：topic0 签名 + from + to，value 在 data 中
        if (topics == null || topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
            return Optional.empty();
        }
        List<TypeReference<Type>> indexedParams = TRANSFER_EVENT.getIndexedParameters();
        List<Type> indexedValues = new ArrayList<>(2);
        indexedValues.add(FunctionReturnDecoder.decodeIndexedValue(topics.get(1), indexedParams.get(0)));
        indexedValues.add(FunctionReturnDecoder.decodeIndexedValue(topics.get(2), indexedParams.get(1)));
        List<Type> nonIndexedValues =
                FunctionReturnDecoder.decode(raw.getData(), TRANSFER_EVENT.getNonIndexedParameters());
        return Optional.of(new EventValues(indexedValues, nonIndexedValues));
    }

    /**
     * 解码为业务对象：from/to 取自 indexed topics，金额取自 data 并按 decimals 换算展示值
     *
     * @param raw      eth_getLogs 返回的原始日志
     * @param decimals 代币精度（由 TokenMetadataService 动态获取）
     * @return Transfer 事件业务对象；非 Transfer 返回 empty
     */
    public static Optional<TransferEventDTO> decode(Log raw, int decimals) {
        return extractEventValues(raw).map(values -> {
            String from = (String) values.getIndexedValues().getFirst().getValue();
            String to = (String) values.getIndexedValues().get(1).getValue();
            BigInteger value = (BigInteger) values.getNonIndexedValues().getFirst().getValue();
            return new TransferEventDTO(
                    raw.getAddress().toLowerCase(),
                    raw.getBlockNumber(),
                    raw.getTransactionHash(),
                    raw.getLogIndex(),
                    from,
                    to,
                    value,
                    toDisplayValue(value, decimals),
                    decimals);
        });
    }

    /**
     * 最小单位整数 → 十进制展示字符串：movePointLeft 保留尾零，与 Etherscan 展示一致
     */
    private static String toDisplayValue(BigInteger rawValue, int decimals) {
        return new BigDecimal(rawValue).movePointLeft(decimals).toPlainString();
    }
}
