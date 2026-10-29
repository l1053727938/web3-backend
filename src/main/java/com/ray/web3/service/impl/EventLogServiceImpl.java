package com.ray.web3.service.impl;

import com.ray.web3.config.InfuraProperties;
import com.ray.web3.model.vo.TransferLogVO;
import com.ray.web3.service.EventLogService;
import com.ray.web3.service.RpcCallService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.EventValues;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Event Log 分页拉取实现：按块跨度分段调用 eth_getLogs，
 * 触发节点结果数上限（如 Infura 单次 10000 条）时自动缩小跨度重试
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventLogServiceImpl implements EventLogService {

    /** ERC-20 Transfer(address,address,uint256) 事件签名哈希 */
    private static final String TOPIC_TRANSFER = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private static final Pattern ADDRESS_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    /** 节点结果数超限错误（如 Infura：query returned more than 10000 results. Try with this block range [0x.., 0x..]） */
    private static final Pattern RESULT_LIMIT_ERROR_PATTERN =
            Pattern.compile("returned more than \\d+ results.*?\\[(0x[0-9a-fA-F]+),\\s*(0x[0-9a-fA-F]+)]", Pattern.CASE_INSENSITIVE);

    /** 节点建议区间的跨度若仍不足 16 块，说明单块日志数就可能超限，停止自适应 */
    private static final BigInteger MIN_CHUNK_SPAN = BigInteger.valueOf(16);

    private final Web3j web3j;
    private final InfuraProperties infuraProperties;
    private final RpcCallService rpcCallService;

    @Override
    public List<TransferLogVO> fetchTransferLogs(String contractAddress, BigInteger fromBlock, BigInteger toBlock) {
        if (contractAddress == null || !ADDRESS_PATTERN.matcher(contractAddress).matches()) {
            throw new IllegalArgumentException("合约地址格式非法: " + contractAddress);
        }
        if (fromBlock == null || toBlock == null || fromBlock.signum() < 0 || fromBlock.compareTo(toBlock) > 0) {
            throw new IllegalArgumentException("区块区间非法: [" + fromBlock + ", " + toBlock + "]");
        }

        // 结束区块不能超过当前链上高度
        BigInteger latest;
        try {
            latest = web3j.ethBlockNumber().send().getBlockNumber();
        } catch (Exception e) {
            throw new IllegalStateException("查询最新区块高度失败: " + e.getMessage(), e);
        }
        if (toBlock.compareTo(latest) > 0) {
            log.info("toBlock={} 超过链上高度={}，自动截断", toBlock, latest);
            toBlock = latest;
        }

        BigInteger chunkSpan = BigInteger.valueOf(infuraProperties.getLogChunkSize());
        List<TransferLogVO> result = new ArrayList<>();
        int chunkCount = 0;

        // 分页循环：每次最多拉 chunkSpan 个块；命中结果数超限则按节点建议缩小跨度重试本段
        for (BigInteger from = fromBlock; from.compareTo(toBlock) <= 0; ) {
            BigInteger to = from.add(chunkSpan).subtract(BigInteger.ONE);
            if (to.compareTo(toBlock) > 0) {
                to = toBlock;
            }

            List<TransferLogVO> chunk;
            try {
                chunk = fetchChunk(buildFilter(contractAddress, from, to), from, to);
            } catch (IllegalStateException e) {
                /*BigInteger suggested = parseSuggestedSpan(e.getMessage());
                if (suggested == null || suggested.compareTo(chunkSpan) >= 0 || suggested.compareTo(MIN_CHUNK_SPAN) < 0) {
                    throw e;
                }*/
//                log.warn("分段 [{},{}] 触发节点结果数上限，跨度 {} → {} 后重试", from, to, chunkSpan, suggested);
//                chunkSpan = suggested;
                log.error("分段 [{},{}] 触发节点结果数上限，跨度 {} ,", from, to, chunkSpan, e);
                throw e;
//                continue;   // 同一起点用新跨度重试本段（进度不丢，前面已拉取的段不受影响）
            }
            result.addAll(chunk);
            chunkCount++;

            from = to.add(BigInteger.ONE);
        }
        log.info("分页拉取完成 contract={} range=[{},{}] chunks={} finalSpan={} logs={}",
                contractAddress, fromBlock, toBlock, chunkCount, chunkSpan, result.size());
        return result;
    }

    private EthFilter buildFilter(String contractAddress, BigInteger from, BigInteger to) {
        EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(from),
                DefaultBlockParameter.valueOf(to),
                contractAddress);
        // 自动生成事件签名哈希，不用手敲 0xddf252ad...
//        filter.addSingleTopic(EventEncoder.encode(TRANSFER_EVENT));
        filter.addNullTopic(); // 添加空主题以匹配所有地址
        return filter;
    }

    // 3. 解析时利用 EventDecoder（不用自己算 substring）
    /*private TransferLogVO toTransferLog(Log log) {
        // 官方解码器会帮你处理 indexed 和非 indexed 的字段
        EventValues eventValues = extractEventValues(TRANSFER_EVENT, log);

        assert eventValues != null;
        String from = eventValues.getIndexedValues().get(0).getValue().toString();
        String to = eventValues.getIndexedValues().get(1).getValue().toString();
        BigInteger value = (BigInteger) eventValues.getNonIndexedValues().getFirst().getValue();
        return new TransferLogVO(
                log.getBlockNumber(),
                log.getTransactionHash(),
                log.getLogIndex(),
                log.getAddress(),
                from,
                to,
                value);
    }*/


    /**
     * 从错误信息解析节点建议的块跨度；无法解析返回 null
     */
    private BigInteger parseSuggestedSpan(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        Matcher m = RESULT_LIMIT_ERROR_PATTERN.matcher(errorMessage);
        if (!m.find()) {
            return null;
        }
        BigInteger sFrom = Numeric.toBigInt(m.group(1));
        BigInteger sTo = Numeric.toBigInt(m.group(2));
        // 节点建议区间含头含尾，跨度 = to - from + 1
        return sTo.subtract(sFrom).add(BigInteger.ONE);
    }

    /**
     * 拉取单个区块分段，并解析 LogObject 为 TransferLogVO（网络层重试由 RpcCallService 承担）
     */
    private List<TransferLogVO> fetchChunk(EthFilter filter, BigInteger from, BigInteger to) {
        EthLog ethLog = rpcCallService.ethGetLogs(filter);
        if (ethLog.hasError()) {
            throw new IllegalStateException(
                    "eth_getLogs 节点返回错误 [" + from + "," + to + "]: " + ethLog.getError().getMessage());
        }

        List<TransferLogVO> chunk = new ArrayList<>();
        for (EthLog.LogResult<?> logResult : ethLog.getLogs()) {
            // Web3j 返回 List<LogResult<?>>，只有 LogObject 才携带具体日志（它本身就是 Log）
            if (!(logResult instanceof EthLog.LogObject logObject)) {
                // 订阅模式下的 Hash 类型结果，直接跳过
                continue;
            }
            chunk.add(toTransferLog(logObject));
        }
        log.debug("分段 [{},{}] 拉取到 {} 条 Transfer 日志", from, to, chunk.size());
        return chunk;
    }

    /*public static EventValues extractEventValues(Event event, Log log) {
        final List<String> topics = log.getTopics();
        String encodedEventSignature = EventEncoder.encode(event);

        // 校验 Topic[0] 是否匹配事件签名
        if (topics == null || topics.isEmpty() || !topics.getFirst().equals(encodedEventSignature)) {
            return null;
        }

        List<Type> indexedValues = new ArrayList<>();
        List<Type> nonIndexedValues = FunctionReturnDecoder.decode(
                log.getData(), event.getNonIndexedParameters());

        List<TypeReference<Type>> indexedParameters = event.getIndexedParameters();
        for (int i = 0; i < indexedParameters.size(); i++) {
            Type value = FunctionReturnDecoder.decodeIndexedValue(
                    topics.get(i + 1), indexedParameters.get(i));
            indexedValues.add(value);
        }
        return new EventValues(indexedValues, nonIndexedValues);
    }*/

    /**
     * 将原始 Log 解析为 Transfer 事件：topic[1]=from，topic[2]=to，data=金额
     */
    private TransferLogVO toTransferLog(Log logObject) {
        List<String> topics = logObject.getTopics();
        String from = topics.size() > 1 ? extractAddress(topics.get(1)) : null;
        String to = topics.size() > 2 ? extractAddress(topics.get(2)) : null;
        BigInteger value = Numeric.toBigInt(logObject.getData());
        return new TransferLogVO(
                logObject.getBlockNumber(),
                logObject.getTransactionHash(),
                logObject.getLogIndex(),
                logObject.getAddress(),
                from,
                to,
                value);
    }

    /**
     * 从 32 字节 topic 中提取末尾 20 字节的地址（小端补齐：0x + 24个0 + 40位地址）
     */
    private String extractAddress(String topic) {
        return "0x" + topic.substring(topic.length() - 40);
    }
}
