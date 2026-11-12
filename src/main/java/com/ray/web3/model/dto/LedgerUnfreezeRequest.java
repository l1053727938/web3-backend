package com.ray.web3.model.dto;

import com.ray.web3.validation.PositiveAmount;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 提币失败解冻请求（第 2 周 Day 5-6 场景④）：{@code LedgerService.unfreeze()} 的 HTTP 入口
 * <p>
 * 分录：用户 {@code frozen -amount} / 用户 {@code available +amount}。
 * 使用独立 bizType=UNFREEZE，同一个提币单号先 FREEZE 再 UNFREEZE 不会撞 uk_biz。
 *
 * @param withdrawNo    原提币单号，与 bizType=UNFREEZE 联合幂等
 * @param userAccountId 用户账户 ID
 * @param amount        解冻金额（正数）
 * @param currency      币种，必须与账户币种一致
 */
public record LedgerUnfreezeRequest(
        @NotBlank(message = "withdrawNo 不能为空")
        @Size(max = 128, message = "withdrawNo 不能超过 128 字符")
        String withdrawNo,

        @NotNull(message = "userAccountId 不能为空")
        @Positive(message = "userAccountId 必须为正数")
        Long userAccountId,

        @PositiveAmount
        String amount,

        @NotBlank(message = "currency 不能为空")
        @Size(max = 16, message = "currency 不能超过 16 字符")
        @Pattern(regexp = "[A-Za-z0-9]+", message = "currency 只能是字母或数字")
        String currency) {
}