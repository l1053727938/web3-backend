package com.ray.web3.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * {@link EnumValue} 的校验实现：在 {@code initialize} 时把枚举常量拼成可用值列表，
 * 校验失败时把列表回填进错误消息，调用方不用再手写 "DEBIT/CREDIT"。
 */
public class EnumValueValidator implements ConstraintValidator<EnumValue, CharSequence> {

    private Class<? extends Enum<?>> enumClass;
    private boolean ignoreCase;
    private boolean allowBlank;
    private String allowedValues;

    @Override
    public void initialize(EnumValue annotation) {
        this.enumClass = annotation.enumClass();
        this.ignoreCase = annotation.ignoreCase();
        this.allowBlank = annotation.allowBlank();
        this.allowedValues = Arrays.stream(enumClass.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.joining("/"));
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        String raw = value.toString().trim();
        if (raw.isEmpty()) {
            return allowBlank;
        }
        boolean matched = Arrays.stream(enumClass.getEnumConstants())
                .anyMatch(constant -> ignoreCase
                        ? constant.name().equalsIgnoreCase(raw)
                        : constant.name().equals(raw));
        if (!matched) {
            // 可用值来自枚举常量而非用户输入，拼进模板是安全的
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("非法枚举值，可用值: " + allowedValues)
                    .addConstraintViolation();
        }
        return matched;
    }
}
