package com.ray.web3.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.web3j.protocol.core.Response;

/**
 * debug_traceTransaction 的 JSON-RPC 响应封装。
 * <p>
 * web3j 6 未在 {@code Web3j} 接口里封装 Debug 命名空间，这里以最小成本接入其通用请求通道：
 * 通过 {@code new Request<>("debug_traceTransaction", params, web3jService, TraceCallResponse.class).send()}
 * 发送，web3j 会把 {@code result} 反序列化到 {@link CallFrame}（callTracer 调用树根节点）。
 * 沿用 web3j 自带响应类的写法：覆写 setResult 挂上 {@code @JsonProperty("result")}，取数交给基类的 getResult/hasError。
 */
public class TraceCallResponse extends Response<CallFrame> {

    @Override
    @JsonProperty("result")
    public void setResult(CallFrame result) {
        super.setResult(result);
    }

    public CallFrame getTrace() {
        return getResult();
    }
}
