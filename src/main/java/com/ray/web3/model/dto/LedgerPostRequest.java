package com.ray.web3.model.dto;

import com.ray.web3.enums.BalanceType;
import com.ray.web3.enums.Direction;
import com.ray.web3.validation.EnumValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 记账请求体（第 2 周 Day 3-4：{@code LedgerService.post()} 的 HTTP 入口）
 * <p>
 * 金额用十进制字符串而不是 JSON 数字：账本精度是 18 位小数，绝大多数前端/脚本
 * （JS Number、部分 JSON 库）会把它截断成 double，字符串口径全链路无损，
 * 也与提币侧 rawValue、充值侧 display_value 的处理方式一致。
 * <p>
 * 本类只表达“请求形状是否合法”（非空、长度、枚举值、金额格式），由 Controller 的
 * {@code @Valid} 触发、{@code GlobalExceptionHandler} 统一转成 400；
 * 账户存在性、余额充足、借贷平衡等业务不变量仍在领域对象/服务层校验。
 *
 * @param bizType  业务类型：DEPOSIT/TRANSFER/FREEZE/FEE/REVERSE
 * @param bizNo    业务单号，与 bizType 联合幂等（同一组合重复提交只会记一次账）
 * @param postings 分录明细，至少两条、同币种借贷相等
 */
public record LedgerPostRequest(
        @NotBlank(message = "bizType 不能为空")
        @Size(max = 32, message = "bizType 不能超过 32 字符")
        String bizType,

        @NotBlank(message = "bizNo 不能为空")
        @Size(max = 128, message = "bizNo 不能超过 128 字符")
        String bizNo,

        @NotEmpty(message = "postings 不能为空")
        @Size(min = 2, message = "一组分录至少两条 posting（有借必有贷）")
        List<@Valid PostingRequest> postings) {

    /**
     * 单条分录
     *
     * @param accountId   账户 ID（account.id）
     * @param direction   方向：DEBIT=余额减少，CREDIT=余额增加
     * @param amount      金额（正数，十进制字符串，如 "100.5"）
     * @param currency    币种，如 USDT
     * @param balanceType 子账类型：AVAILABLE（默认）/FROZEN
     */
    public record PostingRequest(
            @NotNull(message = "accountId 不能为空")
            @Positive(message = "accountId 必须为正数")
            Long accountId,

            @NotBlank(message = "direction 不能为空")
            @EnumValue(enumClass = Direction.class)
            String direction,

            @NotBlank(message = "amount 不能为空（十进制字符串）")
            @Pattern(regexp = "\\d+(?:\\.\\d{1,18})?", message = "amount 必须是整数或最多 18 位小数的十进制字符串")
            @Digits(integer = 20, fraction = 18, message = "amount 整数位不能超过 20 位、小数位不能超过 18 位")
            @DecimalMin(value = "0", inclusive = false, message = "amount 必须大于 0")
            String amount,

            @NotBlank(message = "currency 不能为空")
            @Size(max = 16, message = "currency 不能超过 16 字符")
            @Pattern(regexp = "[A-Za-z0-9]+", message = "currency 只能是字母或数字")
            String currency,

            @EnumValue(enumClass = BalanceType.class, allowBlank = true)
            String balanceType) {
    }
}
