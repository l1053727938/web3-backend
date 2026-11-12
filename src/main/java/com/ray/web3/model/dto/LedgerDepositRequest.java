package com.ray.web3.model.dto;

import com.ray.web3.validation.PositiveAmount;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 充值落账请求（第 2 周 Day 5-6 场景①）：{@code LedgerService.deposit()} 的 HTTP 入口
 * <p>
 * 分录：用户 {@code available +amount} / 平台代收负债 {@code available -amount}。
 * 调用方必须先确认充值单已到账（{@code deposit_record.status=CREDITED}），服务层不判断链上确认数。
 *
 * @param depositNo                 充值单号，与 bizType=DEPOSIT 联合幂等
 * @param userAccountId             用户账户 ID（account.id）
 * @param platformLiabilityAccountId 平台代收负债账户 ID（account_type=PLATFORM）
 * @param amount                    到账金额（正数、十进制字符串）
 * @param currency                  币种，必须与两个账户的币种一致
 */
public record LedgerDepositRequest(
        @NotBlank(message = "depositNo 不能为空")
        @Size(max = 128, message = "depositNo 不能超过 128 字符")
        String depositNo,

        @NotNull(message = "userAccountId 不能为空")
        @Positive(message = "userAccountId 必须为正数")
        Long userAccountId,

        @NotNull(message = "platformLiabilityAccountId 不能为空")
        @Positive(message = "platformLiabilityAccountId 必须为正数")
        Long platformLiabilityAccountId,

        @PositiveAmount
        String amount,

        @NotBlank(message = "currency 不能为空")
        @Size(max = 16, message = "currency 不能超过 16 字符")
        @Pattern(regexp = "[A-Za-z0-9]+", message = "currency 只能是字母或数字")
        String currency) {
}