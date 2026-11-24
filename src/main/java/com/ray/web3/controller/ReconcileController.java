package com.ray.web3.controller;

import com.ray.web3.model.entity.ReconcileDiff;
import com.ray.web3.model.vo.ApiResponse;
import com.ray.web3.service.ReconcileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账接口（第 5 周 Day 3-4）
 * <p>
 * 内部运维接口：手动触发某日对账、查询差异明细。
 * 当前未纳入 JWT 拦截（{@code /api/reconcile} 不在 protected 路径内），生产接入前应加鉴权与审计。
 */
@Tag(name = "对账", description = "按日窗口对账：链上事实 vs 账本，输出六类差异与三类报表口径")
@RestController
@RequestMapping("/api/reconcile")
@RequiredArgsConstructor
public class ReconcileController {

    private final ReconcileService reconcileService;

    @Operation(summary = "执行对账",
            description = "按 reportDate 对应自然日重建 reconcile_diff；不传默认今天。"
                    + "同一日期重复执行会先删后插，结果幂等。")
    @PostMapping("/run")
    public ApiResponse<ReconcileService.ReconcileReport> run(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate reportDate = date == null ? LocalDate.now() : date;
        return ApiResponse.ok(reconcileService.run(reportDate));
    }

    @Operation(summary = "查询差异明细",
            description = "返回某报告日的 reconcile_diff，按报表口径（在途/长短款/待定性）排序。")
    @GetMapping("/diff")
    public ApiResponse<List<ReconcileDiff>> diff(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate reportDate = date == null ? LocalDate.now() : date;
        return ApiResponse.ok(reconcileService.list(reportDate));
    }
}
