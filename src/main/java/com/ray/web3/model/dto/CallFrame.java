package com.ray.web3.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * debug_traceTransaction（callTracer）返回的调用树节点。
 * <p>
 * 每个节点表示一次 EVM 调用：{@code type}（CALL/DELEGATECALL/STATICCALL/CREATE/SELFDESTRUCT…）、
 * {@code from}/{@code to}、{@code value}（本次调用转移的 wei，hex）、以及嵌套的 {@code calls} 子树。
 * 只有 {@code type=CALL} 且 {@code value>0} 的节点才代表一次真实的内部 ETH 转账；
 * {@code error} 非空表示该调用已 revert（状态被撤销），其价值并未真正划转，需排除。
 * <p>
 * 该结构不要求字段齐全（不同节点 gas/input/output 可能缺省），故忽略未知/缺失属性。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CallFrame {

    @JsonProperty("type")
    private String type;

    @JsonProperty("from")
    private String from;

    @JsonProperty("to")
    private String to;

    /** 本次调用携带的 ETH 金额（wei，hex 字符串），CALL 之外通常缺省 */
    @JsonProperty("value")
    private String value;

    @JsonProperty("gas")
    private String gas;

    @JsonProperty("gasUsed")
    private String gasUsed;

    @JsonProperty("input")
    private String input;

    @JsonProperty("output")
    private String output;

    /** 调用出错原因（如 out of gas / revert）；非空则该节点及其子树未生效 */
    @JsonProperty("error")
    private String error;

    /** 嵌套子调用 */
    @JsonProperty("calls")
    private List<CallFrame> calls;

}
