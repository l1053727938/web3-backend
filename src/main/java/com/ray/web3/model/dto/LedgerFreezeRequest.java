package com.ray.web3.model.dto;

import com.ray.web3.validation.PositiveAmount;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 下单冻结请求（第 2 周 Day 5-6 场景②）：{@code LedgerService.freeze()} 的 HTTP 入口
 * <p>
 * 分录：用户 {@code available -amount} / 用户 {@code frozen +amount}，总额不变。
 * 两条分录落在同一个 account_id 的两个子账上，组内借贷仍然相等。
 *
 * @param orderNo       订单号，与 bizType=FREEZE 联合幂等
 * @param userAccountId 用户账户 ID
 * @param amount        冻结金额（正数）
 * @param currency      币种，必须与账户币种一致
 */
public record LedgerFreezeRequest(
        @NotBlank(message = "orderNo 不能为空")
        @Size(max = 128, message = "orderNo 不能超过 128 字符")
        String orderNo,

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