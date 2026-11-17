package com.ray.web3.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户注册请求（第 3 周）
 * <p>
 * 用户名只允许字母、数字、下划线，3-64 位：登录名会出现在日志与运维查询里，
 * 放行任意字符会带来日志注入与展示问题；真正的邮箱/手机号校验属于登录体系，后续再加。
 *
 * @param username 登录名
 */
public record UserRegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 64, message = "用户名长度必须在 3-64 之间")
        @Pattern(regexp = "[A-Za-z0-9_]+", message = "用户名只能包含字母、数字、下划线")
        String username) {
}
