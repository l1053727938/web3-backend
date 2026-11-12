package com.ray.web3.model.dto;

import com.ray.web3.validation.PositiveAmount;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 成交清算请求（第 2 周 Day 5-6 场景③）：{@code LedgerService.settle()} 的 HTTP 入口
 * <p>
 * 一次调用在同一个事务里写两组分录：
 * <ul>
 *   <li>本金（bizType=TRANSFER）：Taker {@code frozen -amount} / Maker {@code available +amount}；</li>
 *   <li>手续费（bizType=FEE，同一 tradeNo）：付款方 {@code available -fee} / 平台手续费账户 {@code available +fee}。</li>
 * </ul>
 * 手续费省略或传 0 时只记本金，响应里的 {@code fee} 为 null。
 *
 * @param tradeNo                成交号，两组分录共用；因 bizType 不同，幂等键互不冲突
 * @param takerAccountId         Taker 账户 ID（扣冻结）
 * @param makerAccountId         Maker 账户 ID（加可用）
 * @param feePayerAccountId      手续费付款方账户 ID（可用扣减）
 * @param platformFeeAccountId   平台手续费收入账户 ID（account_type=PLATFORM）
 * @param amount                 成交本金（正数）
 * @param fee                    手续费（可选，非负；省略/null/0 表示不收手续费）
 * @param currency               币种，必须与全部账户币种一致
 */
public record LedgerSettleRequest(
        @NotBlank(message = "tradeNo 不能为空")
        @Size(max = 128, message = "tradeNo 不能超过 128 字符")
        String tradeNo,

        @NotNull(message = "takerAccountId 不能为空")
        @Positive(message = "takerAccountId 必须为正数")
        Long takerAccountId,

        @NotNull(message = "makerAccountId 不能为空")
        @Positive(message = "makerAccountId 必须为正数")
        Long makerAccountId,

        @NotNull(message = "feePayerAccountId 不能为空")
        @Positive(message = "feePayerAccountId 必须为正数")
        Long feePayerAccountId,

        @NotNull(message = "platformFeeAccountId 不能为空")
        @Positive(message = "platformFeeAccountId 必须为正数")
        Long platformFeeAccountId,

        @PositiveAmount
        String amount,

        @Pattern(regexp = "\\d+(?:\\.\\d{1,18})?",
                message = "fee 必须是整数或最多 18 位小数的十进制字符串")
        @Digits(integer = 20, fraction = 18, message = "fee 整数位不能超过 20 位、小数位不能超过 18 位")
        String fee,

        @NotBlank(message = "currency 不能为空")
        @Size(max = 16, message = "currency 不能超过 16 字符")
        @Pattern(regexp = "[A-Za-z0-9]+", message = "currency 只能是字母或数字")
        String currency) {
}