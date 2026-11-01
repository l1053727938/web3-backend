package com.ray.web3.util;

import com.ray.web3.model.dto.CallFrame;
import com.ray.web3.model.vo.InternalTransferVO;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * callTracer 调用树解码器（需求一/需求三产出）：从 debug_traceTransaction 的嵌套 {@code calls} 中
 * 递归提取所有真实发生 ETH 划转的内部交易，输出 (from, to, value) 列表。
 * <p>
 * 判定口径（与 Etherscan「内部交易」标签页对齐）：
 * <ul>
 *   <li>只看 {@code type == CALL}：DELEGATECALL/STATICCALL 不携带独立价值，CREATE 的 value 不体现在此类节点；</li>
 *   <li>{@code value > 0}：零值 CALL 只是合约方法调用，不是转账；</li>
 *   <li>{@code error} 为空：调用 revert 时状态整体撤销，价值并未转移，不能计入（否则与链上最终态不符）。</li>
 * </ul>
 * 无外部依赖，纯函数式递归，便于离线单测直接构造调用树验证。
 */
public final class CallTraceDecoder {

    private static final String TYPE_CALL = "CALL";

    private CallTraceDecoder() {
    }

    /**
     * 递归收集调用树中所有内部 ETH 转账
     *
     * @param root callTracer 返回的根调用节点（可为 null）
     * @return 按调用树前序遍历得到的内部转账列表
     */
    public static List<InternalTransferVO> extractEthTransfers(CallFrame root) {
        List<InternalTransferVO> result = new ArrayList<>();
        walk(root, result);
        return result;
    }

    private static void walk(CallFrame node, List<InternalTransferVO> out) {
        if (node == null) {
            return;
        }
        BigInteger value = decodeValue(node.getValue());
        if (TYPE_CALL.equalsIgnoreCase(node.getType())
                && value.signum() > 0
                && node.getError() == null) {
            out.add(new InternalTransferVO(
                    normalize(node.getFrom()),
                    normalize(node.getTo()),
                    value,
                    toDisplayValue(value)));
        }
        List<CallFrame> calls = node.getCalls();
        if (calls != null) {
            for (CallFrame child : calls) {
                walk(child, out);
            }
        }
    }

    /**
     * wei hex 字符串 → BigInteger：缺省/空串按 0 处理（Geth 对非 CALL 节点常省略 value）
     */
    private static BigInteger decodeValue(String hex) {
        if (hex == null || hex.isBlank() || "0x".equals(hex)) {
            return BigInteger.ZERO;
        }
        try {
            return Numeric.decodeQuantity(hex);
        } catch (RuntimeException e) {
            // 非法 hex 不参与金额判定，宁可不记账也不误报，交由上层日志排查
            return BigInteger.ZERO;
        }
    }

    private static String normalize(String address) {
        return address == null ? null : address.toLowerCase();
    }

    /**
     * wei → ETH 十进制字符串（18 位），与 Etherscan 展示口径一致
     */
    private static String toDisplayValue(BigInteger wei) {
        return Convert.fromWei(wei.toString(), Convert.Unit.ETHER).toPlainString();
    }
}
