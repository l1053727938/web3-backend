package com.ray.web3.controller;

import com.ray.web3.model.dto.WithdrawSubmitRequest;
import com.ray.web3.model.entity.WithdrawRecord;
import com.ray.web3.model.vo.ApiResponse;
import com.ray.web3.service.Erc20WithdrawService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;

/**
 * ERC-20 提币对外接口（第 3 周：写与发交易）。
 * <p>
 * 路径前缀 {@code /api/chain/withdraw} 落在 JWT 拦截范围（{@code /api/chain/**}）内，提交需
 * {@code withdraw:submit} scope、查询需 {@code withdraw:read}；同时纳入入口限流范围防并发刷提。
 * 提币服务受 {@code web3.withdraw.enabled} 门控，用 ObjectProvider 延迟取得，关闭时接口返回明确提示。
 */
@Tag(name = "提币发交易", description = "热钱包 ERC-20 提币：构造→估算→Nonce→离线签名→广播")
@RestController
@RequestMapping("/api/chain/withdraw")
@RequiredArgsConstructor
public class WithdrawController {

    /** 提币服务受 web3.withdraw.enabled 控制，关闭时 getIfAvailable()=null，由 requireService 给明确提示 */
    private final ObjectProvider<Erc20WithdrawService> erc20WithdrawService;

    @Operation(summary = "提交 ERC-20 提币",
            description = "同步执行到\"已广播\"并返回落库记录（status=BROADCAST 含 txHash 供后续追踪）。"
                    + "rawValue 为代币最小单位（wei 口径）十进制字符串；余额不足/合约 Revert 会在估算阶段拦下，"
                    + "此时未消耗 Nonce。需 scope withdraw:submit")
    @PostMapping("/erc20")
    public ApiResponse<WithdrawRecord> submitErc20(@RequestBody WithdrawSubmitRequest request) {
        BigInteger rawValue = parseRawValue(request.rawValue());
        WithdrawRecord record = requireService()
                .submitWithdraw(request.tokenContract(), request.recipient(), rawValue);
        return ApiResponse.ok(record);
    }

    @Operation(summary = "查询提币记录",
            description = "按提币单号（WD 前缀）查当前状态：INIT 已落库待广播/广播结果不确定、BROADCAST 已上链待确认、FAILED 失败。需 scope withdraw:read")
    @GetMapping("/{withdrawNo}")
    public ApiResponse<WithdrawRecord> query(@PathVariable String withdrawNo) {
        return ApiResponse.ok(requireService().queryWithdraw(withdrawNo));
    }

    /** 金额字符串 → BigInteger：非法（非数字/空）归为参数错误 400，与钱包地址等入参校验口径一致 */
    private BigInteger parseRawValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("提币金额不能为空（代币最小单位十进制字符串）");
        }
        try {
            return new BigInteger(rawValue.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("提币金额必须为十进制整数: " + rawValue);
        }
    }

    private Erc20WithdrawService requireService() {
        Erc20WithdrawService service = erc20WithdrawService.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("提币服务未启用（web3.withdraw.enabled=false）");
        }
        return service;
    }
}
