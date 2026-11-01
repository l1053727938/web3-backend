package com.ray.web3.util;

import com.ray.web3.consts.EventConsts;
import com.ray.web3.model.dto.TransferEventDTO;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public final class TransferEventDecoder {

    private static final Event TRANSFER_EVENT = EventConsts.TRANSFER_EVENT;

    /** Transfer(address,address,uint256) 的 topic0 = keccak 签名，0x 前缀 32 字节 hex */
    private static final String TRANSFER_TOPIC = EventEncoder.encode(TRANSFER_EVENT);

    /** 标准形态的 topic 数量下限：topic0 签名 + 各 indexed 参数（value 在 data 中），由蓝图算出，避免与蓝图漂移 */
    private static final int MIN_TOPIC_SIZE = TRANSFER_EVENT.getIndexedParameters().size() + 1;

    /** 期望从 data 解出的非 indexed 参数个数（uint256 value） */
    private static final int NON_INDEXED_SIZE = TRANSFER_EVENT.getNonIndexedParameters().size();

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
     * <p>
     * 返回的 EventValues 形态受校验：indexed 值恰为 2 个、非 indexed 值恰为 1 个，
     * 因此调用方可直接取 getFirst()/get(1)，无需再判空
     */
    public static Optional<EventValues> extractEventValues(Log raw) {
        if (raw == null) {
            return Optional.empty();
        }
        List<String> topics = raw.getTopics();
        // 标准形态至少 3 项 topic：topic0 签名 + from + to，value 在 data 中
        if (topics == null || topics.size() < MIN_TOPIC_SIZE || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
            return Optional.empty();
        }
        List<TypeReference<Type>> indexedParams = TRANSFER_EVENT.getIndexedParameters();
        List<Type> indexedValues = new ArrayList<>(indexedParams.size());
        for (int i = 0; i < indexedParams.size(); i++) {
            indexedValues.add(FunctionReturnDecoder.decodeIndexedValue(topics.get(i + 1), indexedParams.get(i)));
        }

        List<Type> nonIndexedValues;
        try {
            nonIndexedValues = FunctionReturnDecoder.decode(raw.getData(), TRANSFER_EVENT.getNonIndexedParameters());
        } catch (RuntimeException e) {
            // data 为 null / 长度非 32 字节整数倍等畸形形态：丢弃并留痕，不让单条脏日志打断整块或整段拉取
            log.warn("[transfer-decode] 跳过 data 畸形的 Transfer 日志 address={} txHash={} logIndex={} data={}: {}",
                    raw.getAddress(), raw.getTransactionHash(), raw.getLogIndex(), raw.getData(), e.getMessage());
            return Optional.empty();
        }
        // 全链扫描时可能命中"同签名但参数全部 indexed"的伪 Transfer 事件（topics 有 4 项、data 为 0x，
        // web3j 对空 data 直接返回空列表），解不出金额就不符合 ERC-20 Transfer 蓝图，按非目标事件丢弃
        if (nonIndexedValues.size() < NON_INDEXED_SIZE) {
            log.warn("[transfer-decode] 跳过 data 缺少金额的 Transfer 日志 address={} txHash={} logIndex={} data={}",
                    raw.getAddress(), raw.getTransactionHash(), raw.getLogIndex(), raw.getData());
            return Optional.empty();
        }
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
        // extractEventValues 已校验形态（2 个 indexed + 1 个非 indexed），此处取值必然存在
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
