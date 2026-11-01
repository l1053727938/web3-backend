package com.ray.web3.util;

import com.ray.web3.model.dto.CallFrame;
import com.ray.web3.model.vo.InternalTransferVO;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * callTracer 调用树解码离线单测（需求一）：验证内部 ETH 转账的筛选口径与递归提取，
 * 无需真实节点，直接构造调用树断言。核心规则：仅 type=CALL、value&gt;0、且未 error 的节点计入。
 */
class CallTraceDecoderTest {

    private static final BigInteger ONE_ETH = new BigInteger("1000000000000000000");
    private static final String ONE_ETH_HEX = "0xde0b6b3a7640000";
    private static final String HALF_ETH_HEX = "0x6f05b59d3b20000";

    private static CallFrame frame(String type, String from, String to, String value, String error, CallFrame... calls) {
        CallFrame f = new CallFrame();
        f.setType(type);
        f.setFrom(from);
        f.setTo(to);
        f.setValue(value);
        f.setError(error);
        if (calls.length > 0) {
            f.setCalls(List.of(calls));
        }
        return f;
    }

    @Test
    void 只提取CALL且value大于零的节点并按前序递归() {
        // 根：用户 0xE992... 调路由合约，自身不带 ETH（value=0）
        CallFrame root = frame("CALL",
                "0xE99294Af71bf88afFB4c8Fh1Da1b1B3E0a9c40AB",
                "0x7a250d5630B4cF539739dF2C5dAcb4c659F2488D",
                "0x0", null,
                // 1) 路由 → 用户充值地址 1 ETH，应计入（大写地址应被归一小写）
                frame("CALL", "0x7A250D5630B4CF539739DF2C5DACB4C659F2488D", "0x1111111111111111111111111111111111111111",
                        ONE_ETH_HEX, null,
                        // 1.1) 充值地址再分账 0.5 ETH 给子地址，验证递归下钻也能取到
                        frame("CALL", "0x1111111111111111111111111111111111111111", "0x2222222222222222222222222222222222222222",
                                HALF_ETH_HEX, null)),
                // 2) DELEGATECALL 不携带独立价值，即使 value>0 也要丢弃
                frame("DELEGATECALL", "0x7a250d5630b4cf539739df2c5dacb4c659f2488d", "0x3333333333333333333333333333333333333333",
                        ONE_ETH_HEX, null),
                // 3) 零值 CALL：只是方法调用，不是转账
                frame("CALL", "0x7a250d5630b4cf539739df2c5dacb4c659f2488d", "0x4444444444444444444444444444444444444444",
                        "0x0", null),
                // 4) 带 error 的 CALL：已 revert，价值未真正转移，不能计入
                frame("CALL", "0x7a250d5630b4cf539739df2c5dacb4c659f2488d", "0x5555555555555555555555555555555555555555",
                        ONE_ETH_HEX, "execution reverted"));

        List<InternalTransferVO> result = CallTraceDecoder.extractEthTransfers(root);

        // 只有 1) 和 1.1) 命中
        assertThat(result).hasSize(2);

        InternalTransferVO first = result.get(0);
        assertThat(first.from()).isEqualTo("0x7a250d5630b4cf539739df2c5dacb4c659f2488d"); // 已归一小写
        assertThat(first.to()).isEqualTo("0x1111111111111111111111111111111111111111");
        assertThat(first.rawValue()).isEqualTo(ONE_ETH);
        assertThat(first.displayValue()).isEqualTo("1");

        InternalTransferVO nested = result.get(1);
        assertThat(nested.from()).isEqualTo("0x1111111111111111111111111111111111111111");
        assertThat(nested.to()).isEqualTo("0x2222222222222222222222222222222222222222");
        assertThat(nested.displayValue()).isEqualTo("0.5");
    }

    @Test
    void 空调用树与缺省value安全返回空() {
        assertThat(CallTraceDecoder.extractEthTransfers(null)).isEmpty();
        // value 为 null（非 CALL 节点常省略）不应抛异常，按 0 处理被过滤
        CallFrame noValue = frame("CALL", "0xaaaa", "0xbbbb", null, null);
        assertThat(CallTraceDecoder.extractEthTransfers(noValue)).isEmpty();
    }
}
