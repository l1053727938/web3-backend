package com.ray.web3.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 校验字符串是不是某个枚举的合法常量名。
 * <p>
 * 相比在 DTO 上写 {@code @Pattern(regexp = "DEBIT|CREDIT")}，可用值直接来自
 * {@link #enumClass()} 的常量，枚举增删时不用同步改两处；{@code null} 按 Bean Validation
 * 惯例视为合法（是否必填交给 {@code @NotBlank}），空串默认非法（可选字段可设
 * {@link #allowBlank()}）。
 */
@Documented
@Constraint(validatedBy = EnumValueValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE,
        ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface EnumValue {

    /** 目标枚举类型，可用值即其常量名 */
    Class<? extends Enum<?>> enumClass();

    /** 是否忽略大小写，默认 true（请求侧允许 debit/DEBIT 混用） */
    boolean ignoreCase() default true;

    /** 是否允许空串；适用于省略时由业务给默认值的可选字段 */
    boolean allowBlank() default false;

    String message() default "非法枚举值";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
