package com.ray.web3.controller;

import com.ray.web3.config.AuthProperties;
import com.ray.web3.model.vo.ApiResponse;
import com.ray.web3.model.vo.TokenVO;
import com.ray.web3.util.JwtTokenProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token 签发接口（本地/联调用）：{@code POST /api/auth/token}。
 * <p>
 * 刻意放在受保护路径 {@code /api/chain/**} 之外，避免被 {@code JwtAuthInterceptor} 拦截形成
 * “签发接口自己也要 token”的死循环。仅在 {@code security.jwt.enabled=true} 时注册（关闭鉴权时本就无需 token）。
 * <p>
 * <b>安全边界</b>：必须带请求头 {@code X-Issuer-Key} 且等于 {@code security.jwt.issuer-access-key}。
 * 未配置口令（留空）时接口返回 503——安全默认，防止 token 被任意调用方铸造。
 * 该接口仅为打通第 2 周鉴权自测提供便利，<b>生产环境应关闭</b>（不设口令即关闭），
 * 正式签发应由独立的用户登录/运营后台模块承担。
 */
@Slf4j
@Tag(name = "Token 签发", description = "联调用 JWT 签发（受 X-Issuer-Key 口令保护）")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "security.jwt", name = "enabled", havingValue = "true")
public class AuthController {

    /** 口令请求头名 */
    private static final String ISSUER_KEY_HEADER = "X-Issuer-Key";

    /** 未指定 scope 时默认授予全部查询权限，方便联调一次通过 */
    private static final String DEFAULT_SCOPE = "chain:read log:query deposit:read";

    private final ObjectProvider<JwtTokenProvider> tokenProvider;
    private final AuthProperties authProperties;

    @Operation(summary = "签发 JWT",
            description = "带请求头 X-Issuer-Key（等于 security.jwt.issuer-access-key）调用；"
                    + "scope 空格分隔，留空默认授予 chain:read log:query deposit:read")
    @PostMapping("/token")
    public ResponseEntity<ApiResponse<TokenVO>> issueToken(
            @RequestHeader(value = ISSUER_KEY_HEADER, required = false) String issuerKey,
            @RequestParam String sub,
            @RequestParam(required = false) String scope) {
        AuthProperties.Jwt jwt = authProperties.getJwt();
        String accessKey = jwt.getIssuerAccessKey();
        // 未配置口令 = 接口关闭（503）；直返回失败体，不走 GlobalExceptionHandler 以免被误当 RPC 异常转 502
        if (accessKey == null || accessKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.fail(503, "签发接口未启用：请配置 security.jwt.issuer-access-key"));
        }
        // 口令不匹配 = 未授权（401）
        if (!accessKey.equals(issuerKey)) {
            log.warn("token 签发口令校验失败 sub={}", sub);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail(401, ISSUER_KEY_HEADER + " 口令不正确"));
        }

        String effectiveScope = (scope == null || scope.isBlank()) ? DEFAULT_SCOPE : scope;
        String token = tokenProvider.getObject().issue(sub, effectiveScope);
        return ResponseEntity.ok(ApiResponse.ok(new TokenVO(token, effectiveScope, jwt.getTtlSeconds())));
    }
}
