package com.ray.web3.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 账本正金额（十进制字符串）组合校验（第 2 周 Day 5-6）
 * <p>
 * 账本金额全链路使用字符串：精度固定 {@code DECIMAL(38,18)}，JSON 数字在 JS/部分脚本里会被
 * double 截断。这里把「非空 + 十进制格式 + 最多 18 位小数 + 不超过 20 位整数 + 大于 0」
 * 收敛成一个注解，四类业务请求共用，避免各 DTO 复制同一组约束后逐渐漂移。
 * <p>
 * 与 {@code LedgerPostRequest.PostingRequest} 上的三条约束同口径；可选金额（如手续费，
 * 允许省略或为 0）不能直接用本注解，需在 DTO 上单独声明 {@code @Pattern/@Digits}。
 */
@Documented
@Constraint(validatedBy = {})
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE,
        ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@NotBlank(message = "金额不能为空（十进制字符串）")
@Pattern(regexp = "\\d+(?:\\.\\d{1,18})?", message = "金额必须是整数或最多 18 位小数的十进制字符串")
@Digits(integer = 20, fraction = 18, message = "金额整数位不能超过 20 位、小数位不能超过 18 位")
@DecimalMin(value = "0", inclusive = false, message = "金额必须大于 0")
public @interface PositiveAmount {

    String message() default "金额不合法";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}