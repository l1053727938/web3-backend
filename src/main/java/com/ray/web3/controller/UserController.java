package com.ray.web3.controller;

import com.ray.web3.config.InfuraProperties;
import com.ray.web3.model.dto.UserRegisterRequest;
import com.ray.web3.model.vo.ApiResponse;
import com.ray.web3.model.vo.DepositAddressVO;
import com.ray.web3.model.vo.UserRegisteredVO;
import com.ray.web3.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户业务接口（第 3 周）：注册 + 充值地址
 * <p>
 * <b>安全边界</b>：当前未纳入 JWT 拦截（{@code /api/chain/**} 之外），注册本身需要匿名可访问；
 * 但 {@code GET /{userId}/deposit-address} 在生产必须换成“当前登录用户查自己”，
 * 不能凭 userId 任意查询，否则就是用户信息枚举接口。登录体系接入前这里只适合本地联调。
 * <p>
 * 注册是幂等的：同名重复提交返回同一个 userId 与同一个地址。
 */
@Tag(name = "用户业务", description = "注册、充值地址分配（HD 派生，一链一地址）")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final InfuraProperties infuraProperties;

    @Operation(summary = "注册用户",
            description = "创建用户并分配当前链的专属充值地址；同名重复提交幂等返回原用户与原地址。"
                    + "注意：账号开户是懒创建，首次入账时由消费者完成。")
    @PostMapping("/register")
    public ApiResponse<UserRegisteredVO> register(@Valid @RequestBody UserRegisterRequest request) {
        UserService.Registered registered = userService.register(request.username());
        return ApiResponse.ok(new UserRegisteredVO(
                registered.userId(), registered.username(), registered.depositAddress()));
    }

    @Operation(summary = "查询充值地址",
            description = "返回用户在当前链上的专属充值地址；地址由 user_id 派生，重复查询结果相同。")
    @GetMapping("/{userId}/deposit-address")
    public ApiResponse<DepositAddressVO> depositAddress(@PathVariable("userId") long userId) {
        String address = userService.depositAddress(userId);
        return ApiResponse.ok(new DepositAddressVO(userId, infuraProperties.getChainId(), address));
    }
}
