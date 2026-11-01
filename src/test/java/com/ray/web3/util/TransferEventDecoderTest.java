package com.ray.web3.util;

import com.ray.web3.model.dto.TransferEventDTO;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ERC-20 Transfer 解码器离线单测：标准日志的取值口径 + 形态异常日志必须"丢弃"而不是"抛异常"
 * <p>
 * 重点回归：按区块全链扫描时可能命中同签名但参数全部 indexed 的伪 Transfer 事件（data 为 0x），
 * 此类日志解不出金额，若放行会让下游 getFirst() 抛 NoSuchElementException 打断整块解析
 */
class TransferEventDecoderTest {

    private static final String CONTRACT = "0xdac17f958d2ee523a2206206994597c13d831ec7";
    private static final String TX_HASH = "0x" + "a".repeat(64);
    private static final String FROM_RAW = "1111111111111111111111111111111111111111";
    private static final String TO_RAW = "2222222222222222222222222222222222222222";
    private static final BigInteger VALUE = BigInteger.valueOf(1_000_000);

    /** 左补齐为 32 字节 topic/data */
    private static String word(String hexTail) {
        return "0x" + "0".repeat(64 - hexTail.length()) + hexTail;
    }

    private static Log log(List<String> topics, String data) {
        Log raw = new Log();
        raw.setAddress(CONTRACT);
        raw.setBlockNumber("0x1234");
        raw.setBlockHash("0x" + "b".repeat(64));
        raw.setTransactionHash(TX_HASH);
        raw.setTransactionIndex("0x0");
        raw.setLogIndex("0x1");
        raw.setRemoved(false);
        raw.setType("mined");
        raw.setTopics(topics);
        raw.setData(data);
        return raw;
    }

    /** topic0=Transfer 签名 + from + to 两项 indexed topic，金额在 data 中 */
    private static Log standardTransfer() {
        return log(List.of(TransferEventDecoder.transferEventTopic(), word(FROM_RAW), word(TO_RAW)),
                word(VALUE.toString(16)));
    }

    @Test
    void 标准Transfer日志解出from_to与金额() {
        Optional<TransferEventDTO> decoded = TransferEventDecoder.decode(standardTransfer(), 6);

        assertThat(decoded).isPresent();
        TransferEventDTO dto = decoded.get();
        assertThat(dto.contract()).isEqualTo(CONTRACT);
        assertThat(dto.from()).isEqualTo("0x" + FROM_RAW);
        assertThat(dto.to()).isEqualTo("0x" + TO_RAW);
        assertThat(dto.rawValue()).isEqualTo(VALUE);
        // movePointLeft 保留尾零，与 Etherscan 展示一致
        assertThat(dto.displayValue()).isEqualTo("1.000000");
        assertThat(dto.blockNumber()).isEqualTo(BigInteger.valueOf(0x1234));
        assertThat(dto.logIndex()).isEqualTo(BigInteger.ONE);
    }

    @Test
    void 同签名但参数全indexed的伪Transfer日志被丢弃且不抛异常() {
        // 事件声明为 Transfer(address indexed, address indexed, uint256 indexed)：topic0 与 ERC-20 完全相同，
        // 金额为第 4 项 topic，data 为空 -> web3j 解码得到空列表
        Log pseudo = log(
                List.of(TransferEventDecoder.transferEventTopic(), word(FROM_RAW), word(TO_RAW), word(VALUE.toString(16))),
                "0x");

        assertThatCode(() -> TransferEventDecoder.extractEventValues(pseudo)).doesNotThrowAnyException();
        assertThat(TransferEventDecoder.extractEventValues(pseudo)).isEmpty();
        assertThat(TransferEventDecoder.decode(pseudo, 6)).isEmpty();
    }

    @Test
    void 畸形data与不合格topics安全返回空() {
        String topic = TransferEventDecoder.transferEventTopic();
        // data 长度不足 32 字节，ABI 解码越界
        Log brokenData = log(List.of(topic, word(FROM_RAW), word(TO_RAW)), "0xf4240");
        // data 字段缺失（null）
        Log nullData = log(List.of(topic, word(FROM_RAW), word(TO_RAW)), null);
        // topic0 非 Transfer 签名
        Log otherEvent = log(List.of("0x" + "c".repeat(64), word(FROM_RAW), word(TO_RAW)), word(VALUE.toString(16)));
        // topics 数量不足（缺 to）
        Log shortTopics = log(List.of(topic, word(FROM_RAW)), word(VALUE.toString(16)));

        assertThatCode(() -> {
            TransferEventDecoder.extractEventValues(brokenData);
            TransferEventDecoder.extractEventValues(nullData);
            TransferEventDecoder.extractEventValues(otherEvent);
            TransferEventDecoder.extractEventValues(shortTopics);
            TransferEventDecoder.extractEventValues(null);
        }).doesNotThrowAnyException();

        assertThat(TransferEventDecoder.extractEventValues(brokenData)).isEmpty();
        assertThat(TransferEventDecoder.extractEventValues(nullData)).isEmpty();
        assertThat(TransferEventDecoder.extractEventValues(otherEvent)).isEmpty();
        assertThat(TransferEventDecoder.extractEventValues(shortTopics)).isEmpty();
        assertThat(TransferEventDecoder.extractEventValues(null)).isEmpty();
    }
}
