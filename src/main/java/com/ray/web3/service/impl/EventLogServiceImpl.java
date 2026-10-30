package com.ray.web3.service.impl;

import com.ray.web3.config.InfuraProperties;
import com.ray.web3.model.dto.TransferEventDTO;
import com.ray.web3.model.vo.TransferLogVO;
import com.ray.web3.service.EventLogService;
import com.ray.web3.service.RpcCallService;
import com.ray.web3.service.TokenMetadataService;
import com.ray.web3.util.ChainParams;
import com.ray.web3.util.ChainQueryUtils;
import com.ray.web3.util.TransferEventDecoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.abi.EventValues;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
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

    /** 节点结果数超限错误（如 Infura：query returned more than 10000 results. Try with this block range [0x.., 0x..]） */
    private static final Pattern RESULT_LIMIT_ERROR_PATTERN =
            Pattern.compile("returned more than \\d+ results.*?\\[(0x[0-9a-fA-F]+),\\s*(0x[0-9a-fA-F]+)]", Pattern.CASE_INSENSITIVE);

    /** 节点上限类错误但无法解析建议区间的形态（如 Alchemy "block range is too wide"、Geth "query returned too many events"） */
    private static final Pattern RANGE_LIMIT_ERROR_PATTERN =
            Pattern.compile("(block range|range too (?:large|wide)|too many (?:results|logs|events)|limit exceeded)",
                    Pattern.CASE_INSENSITIVE);

    /** 节点建议区间的跨度若仍不足 16 块，说明单块日志数就可能超限，停止自适应 */
    private static final BigInteger MIN_CHUNK_SPAN = BigInteger.valueOf(16);

    private final Web3j web3j;
    private final InfuraProperties infuraProperties;
    private final RpcCallService rpcCallService;
    private final TokenMetadataService tokenMetadataService;

    @Override
    public List<TransferLogVO> fetchTransferLogs(String contractAddress, BigInteger fromBlock, BigInteger toBlock) {
        List<TransferLogVO> result = new ArrayList<>();
        for (Log raw : collectLogs(contractAddress, fromBlock, toBlock)) {
            // 按 Transfer 蓝图解码并校验 topic[0]，非目标事件直接丢弃
            TransferEventDecoder.extractEventValues(raw)
                    .ifPresent(values -> result.add(toTransferLog(raw, values)));
        }
        return result;
    }

    @Override
    public List<TransferEventDTO> fetchDecodedTransferLogs(String contractAddress, BigInteger fromBlock, BigInteger toBlock) {
        return decodeAll(contractAddress, collectLogs(contractAddress, fromBlock, toBlock));
    }

    @Override
    public List<TransferEventDTO> fetchTransferLogPage(String contractAddress, BigInteger fromBlock, BigInteger toBlock) {
        ChainParams.requireContract(contractAddress);
        ChainParams.requireRange(fromBlock, toBlock);
        // 补拉按页推进游标：区间以入参为准（链高截断由调用方完成），页内命中节点上限时自动缩跨度续拉
        return decodeAll(contractAddress, fetchLogsAdaptive(contractAddress, fromBlock, toBlock));
    }

    @Override
    public List<TransferEventDTO> fetchTransfersByTx(String txHash) {
        ChainParams.requireTxHash(txHash);
        EthGetTransactionReceipt receipt;
        try {
            receipt = web3j.ethGetTransactionReceipt(txHash).send();
        } catch (Exception e) {
            throw new IllegalStateException("查询交易收据失败: " + e.getMessage(), e);
        }
        if (receipt.hasError()) {
            throw new IllegalStateException("eth_getTransactionReceipt 节点返回错误: " + receipt.getError().getMessage());
        }
        TransactionReceipt txReceipt = receipt.getTransactionReceipt()
                .orElseThrow(() -> new IllegalArgumentException("交易不存在或尚未上块: " + txHash));

        List<TransferEventDTO> result = new ArrayList<>();
        for (Log raw : txReceipt.getLogs()) {
            // 单笔交易可能触及多个代币合约，精度按各日志的合约地址动态获取
            TransferEventDecoder.decode(raw, tokenMetadataService.decimals(raw.getAddress()))
                    .ifPresent(result::add);
        }
        return result;
    }

    /**
     * 参数校验 + 链上高度截断 + 按块跨度分段循环拉取，返回区间内的原始日志
     */
    private List<Log> collectLogs(String contractAddress, BigInteger fromBlock, BigInteger toBlock) {
        ChainParams.requireContract(contractAddress);
        ChainParams.requireRange(fromBlock, toBlock);

        // 结束区块不能超过当前链上高度
        BigInteger latest = ChainQueryUtils.latestBlockNumber(web3j);
        if (toBlock.compareTo(latest) > 0) {
            log.info("toBlock={} 超过链上高度={}，自动截断", toBlock, latest);
            toBlock = latest;
        }

        BigInteger chunkSpan = BigInteger.valueOf(infuraProperties.getLogChunkSize());
        List<Log> result = new ArrayList<>();
        int chunkCount = 0;

        // 分页循环：每次最多拉 chunkSpan 个块；段内命中节点上限由 fetchLogsAdaptive 缩跨度续拉
        for (BigInteger from = fromBlock; from.compareTo(toBlock) <= 0; ) {
            BigInteger to = from.add(chunkSpan).subtract(BigInteger.ONE);
            if (to.compareTo(toBlock) > 0) {
                to = toBlock;
            }
            result.addAll(fetchLogsAdaptive(contractAddress, from, to));
            chunkCount++;

            from = to.add(BigInteger.ONE);
        }
        log.info("分页拉取完成 contract={} range=[{},{}] chunks={} logs={}",
                contractAddress, fromBlock, toBlock, chunkCount, result.size());
        return result;
    }

    /**
     * 拉取一个区块分段的全部日志：命中节点单次结果数/区块跨度上限时自动缩小跨度续拉
     * （优先采纳节点建议跨度，无建议则取当前跨度一半），已到 MIN_CHUNK_SPAN 护栏仍被拒则原样抛出
     * <p>
     * 缩跨度只改变请求切分方式、不改变结果集合：各子段日志按区块升序合并后仍是本段完整日志，
     * 因此调用方可以安全地把游标推进到本段末尾
     */
    private List<Log> fetchLogsAdaptive(String contractAddress, BigInteger from, BigInteger to) {
        try {
            return fetchChunk(buildFilter(contractAddress, from, to), from, to);
        } catch (IllegalStateException e) {
            BigInteger span = to.subtract(from).add(BigInteger.ONE);
            if (!isNodeLimitError(e.getMessage()) || span.compareTo(MIN_CHUNK_SPAN) <= 0) {
                // 非上限类错误（网络/节点故障）缩跨度也无意义，直接抛出交给 rpc-segment 重试与熔断
                throw e;
            }
            BigInteger subSpan = nextSpan(e.getMessage(), span);
            log.warn("分段 [{},{}] 命中节点上限，跨度 {} → {} 后续拉: {}", from, to, span, subSpan, e.getMessage());

            List<Log> logs = new ArrayList<>();
            for (BigInteger cursor = from; cursor.compareTo(to) <= 0; ) {
                BigInteger cursorTo = cursor.add(subSpan).subtract(BigInteger.ONE);
                if (cursorTo.compareTo(to) > 0) {
                    cursorTo = to;
                }
                logs.addAll(fetchLogsAdaptive(contractAddress, cursor, cursorTo));
                cursor = cursorTo.add(BigInteger.ONE);
            }
            return logs;
        }
    }

    /**
     * 下一个尝试跨度：优先取节点建议跨度（通常已按结果密度算好），建议不合理时退化为二分，保证单调收敛
     */
    private BigInteger nextSpan(String errorMessage, BigInteger span) {
        BigInteger suggested = parseSuggestedSpan(errorMessage);
        if (suggested != null && suggested.signum() > 0 && suggested.compareTo(span) < 0) {
            return suggested;
        }
        BigInteger half = span.divide(BigInteger.TWO);
        return half.signum() > 0 ? half : BigInteger.ONE;
    }

    /**
     * 是否为节点侧上限类错误，只有这类错误缩小跨度重试才有意义
     */
    private boolean isNodeLimitError(String errorMessage) {
        return errorMessage != null
                && (RESULT_LIMIT_ERROR_PATTERN.matcher(errorMessage).find()
                || RANGE_LIMIT_ERROR_PATTERN.matcher(errorMessage).find());
    }

    /**
     * 分批解析：同一合约精度只取一次（动态 decimals() + 结果缓存），非 Transfer 日志在解码阶段丢弃
     */
    private List<TransferEventDTO> decodeAll(String contractAddress, List<Log> logs) {
        int decimals = tokenMetadataService.decimals(contractAddress);
        List<TransferEventDTO> result = new ArrayList<>(logs.size());
        for (Log raw : logs) {
            TransferEventDecoder.decode(raw, decimals).ifPresent(result::add);
        }
        return result;
    }

    private EthFilter buildFilter(String contractAddress, BigInteger from, BigInteger to) {
        EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(from),
                DefaultBlockParameter.valueOf(to),
                contractAddress);
        // 按事件蓝图自动生成的 topic[0] 在节点侧过滤 Transfer，避免把合约全部日志拉回本地再丢弃
        filter.addSingleTopic(TransferEventDecoder.transferEventTopic());
        return filter;
    }

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
     * 拉取单个区块分段的原始日志（网络层重试由 RpcCallService 承担）
     */
    private List<Log> fetchChunk(EthFilter filter, BigInteger from, BigInteger to) {
        EthLog ethLog = rpcCallService.ethGetLogs(filter);
        if (ethLog.hasError()) {
            throw new IllegalStateException(
                    "eth_getLogs 节点返回错误 [" + from + "," + to + "]: " + ethLog.getError().getMessage());
        }

        List<Log> chunk = new ArrayList<>();
        for (EthLog.LogResult<?> logResult : ethLog.getLogs()) {
            // Web3j 返回 List<LogResult<?>>，只有 LogObject 才携带具体日志（它本身就是 Log）
            if (!(logResult instanceof EthLog.LogObject logObject)) {
                // 订阅模式下的 Hash 类型结果，直接跳过
                continue;
            }
            chunk.add(logObject);
        }
        log.debug("分段 [{},{}] 拉取到 {} 条原始日志", from, to, chunk.size());
        return chunk;
    }

    /**
     * 解码结果映射为既有视图对象：from/to 取自 indexed topics，金额取自 data（已在解码阶段丢弃非 Transfer 事件）
     */
    private TransferLogVO toTransferLog(Log logObject, EventValues values) {
        String from = (String) values.getIndexedValues().getFirst().getValue();
        String to = (String) values.getIndexedValues().get(1).getValue();
        BigInteger value = (BigInteger) values.getNonIndexedValues().getFirst().getValue();
        return new TransferLogVO(
                logObject.getBlockNumber(),
                logObject.getTransactionHash(),
                logObject.getLogIndex(),
                logObject.getAddress(),
                from,
                to,
                value);
    }
}
