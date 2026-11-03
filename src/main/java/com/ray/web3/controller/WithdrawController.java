package com.ray.web3.controller;

import com.ray.web3.model.dto.WithdrawSubmitRequest;
import com.ray.web3.model.entity.WithdrawRecord;
import com.ray.web3.model.vo.ApiResponse;
import com.ray.web3.service.Erc20WithdrawService;
import com.ray.web3.service.WithdrawTrackingService;
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
 * 路径前缀 {@code /api/chain/withdraw} 落在 JWT 拦截范围（{@code /api/chain/**}）内，提交/加速/撤销需
 * POST scope、查询与诊断需 {@code withdraw:read}；同时纳入入口限流范围防并发刷提。
 * 提币服务受 {@code web3.withdraw.enabled} 门控，用 ObjectProvider 延迟取得，关闭时接口返回明确提示。
 * <p>
 * Day 5-7 新增的两个写接口都是<b>人工授权动作</b>：自动加速只能提价重播，而“作废一笔提币”（撤销）
 * 与“超预算后继续推”（手动加速）必须有人插手，所以不进自动流程、只给接口
 */
@Tag(name = "提币发交易", description = "热钱包 ERC-20 提币：构造→估算→Nonce→离线签名→广播→追踪→加速/撤销")
@RestController
@RequestMapping("/api/chain/withdraw")
@RequiredArgsConstructor
public class WithdrawController {

    /** 提币服务受 web3.withdraw.enabled 控制，关闭时 getIfAvailable()=null，由 requireService 给明确提示 */
    private final ObjectProvider<Erc20WithdrawService> erc20WithdrawService;
    /** 诊断能力属追踪服务（同受 enabled 门控），仅只读查询成因，不触发任何链上动作 */
    private final ObjectProvider<WithdrawTrackingService> withdrawTrackingService;

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

    @Operation(summary = "诊断卡单成因",
            description = "只读回答“这笔 BROADCAST 为何还没结果”：QUEUED 内存池排队/DROPPED 被丢弃/" +
                    "REPLACED 同 nonce 已被覆盖/AWAITING_CONFIRMATION 已中块等确认/NODE_UNAVAILABLE 节点问不到；" +
                    "非 BROADCAST 或单号不存在返回 null。不写库、不发交易，可安全高频调用。需 scope withdraw:read")
    @GetMapping("/{withdrawNo}/stuck-state")
    public ApiResponse<WithdrawTrackingService.StuckState> diagnose(@PathVariable String withdrawNo) {
        WithdrawTrackingService service = withdrawTrackingService.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("提币追踪服务未启用（web3.withdraw.enabled=false）");
        }
        return ApiResponse.ok(service.diagnoseStuck(withdrawNo));
    }

    @Operation(summary = "手动加速提币",
            description = "用本行已占的同一 Nonce、逐字节复现转账字段、只把 gas 单价提高后重新广播（Replace-by-fee）。"
                    + "自动加速达次数上限或候选价超封顶时由本接口人工接手；不受 accelerate-max-count 约束，"
                    + "但仍受单价封顶约束。需提价幅度足够，否则节点会以 underpriced 拒绝。需 scope 提交类")
    @PostMapping("/{withdrawNo}/accelerate")
    public ApiResponse<WithdrawRecord> accelerate(@PathVariable String withdrawNo) {
        return ApiResponse.ok(requireService().accelerateWithdraw(withdrawNo));
    }

    @Operation(summary = "撤销卡单提币",
            description = "用同一 Nonce 广播一笔“自己转给自己”的 0 值空交易顶掉原提币交易，原单转 REPLACED 作废。"
                    + "只完成链上动作，<b>不退款也不解冻</b>（账务侧属第 8 周业务编排），调用前需自行确认下游影响。"
                    + "需 scope 提交类")
    @PostMapping("/{withdrawNo}/cancel")
    public ApiResponse<WithdrawRecord> cancel(@PathVariable String withdrawNo) {
        return ApiResponse.ok(requireService().cancelWithdraw(withdrawNo));
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
