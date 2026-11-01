package com.ray.web3.service.impl;

import com.ray.web3.model.dto.CallFrame;
import com.ray.web3.model.dto.TraceCallResponse;
import com.ray.web3.model.vo.InternalTransferVO;
import com.ray.web3.service.DebugTraceService;
import com.ray.web3.util.CallTraceDecoder;
import com.ray.web3.util.ChainParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.Request;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 内部交易追溯实现（需求一 + 需求三）：web3j 6 未封装 Debug 命名空间，
 * 这里经底层 {@link Web3jService} 直接发起自定义 JSON-RPC —— {@code debug_traceTransaction} 配 {@code callTracer}，
 * 再把返回的调用树交给 {@link CallTraceDecoder} 递归提取内部 ETH 转账。
 * <p>
 * 不加 rpc/rpc-segment 弹性注解：本接口对不支持它的节点（如 Infura 公共/免费端点）恒定返回方法不存在错误，
 * 重试无意义，只会徒增 RPC 消耗；失败直接以 IllegalStateException 上抛，由 GlobalExceptionHandler 转 502。
 * <p>
 * 注入的 {@code web3jService} 按 Bean 名匹配到 {@link com.ray.web3.config.Web3jConfig#web3jService} 的 HTTP 通道，
 * 在与 wsWeb3j 的 WebSocketService（同为 Web3jService）共存时靠名称消歧，复用它建连与代理配置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DebugTraceServiceImpl implements DebugTraceService {

    /** callTracer 追踪器参数：返回嵌套调用树，而非默认 Opcode 步进日志 */
    private static final Map<String, Object> CALL_TRACER = Map.of("tracer", "callTracer");

    private final Web3jService web3jService;

    @Override
    public List<InternalTransferVO> traceInternalTransfers(String txHash) {
        ChainParams.requireTxHash(txHash);
        TraceCallResponse response;
        try {
            Request<Object, TraceCallResponse> request = new Request<>(
                    "debug_traceTransaction",
                    Arrays.asList(txHash, CALL_TRACER),
                    web3jService,
                    TraceCallResponse.class);
            response = request.send();
        } catch (Exception e) {
            throw new IllegalStateException("debug_traceTransaction RPC 请求失败: " + e.getMessage(), e);
        }
        if (response.hasError()) {
            throw new IllegalStateException("debug_traceTransaction 调用失败（" + response.getError().getMessage()
                    + "）：该节点可能未开放 Debug 命名空间，Infura 公共/免费端点不可用，请改用自建归档节点或本地 Anvil");
        }
        CallFrame root = response.getResult();
        if (root == null) {
            // 交易不存在/未上链时部分节点返回 result 为空，视作无可追溯内容而非异常
            log.warn("debug_traceTransaction 返回空调用树, txHash={}", txHash);
            return List.of();
        }
        List<InternalTransferVO> transfers = CallTraceDecoder.extractEthTransfers(root);
        log.info("[debug-trace] txHash={} 提取内部 ETH 转账 {} 笔", txHash, transfers.size());
        return transfers;
    }
}
