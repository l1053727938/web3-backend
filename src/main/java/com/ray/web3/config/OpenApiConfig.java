package com.ray.web3.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档增强：为 Swagger UI 注册 Bearer JWT 安全方案。
 * <p>
 * 注册后 Swagger UI 右上角出现 <b>Authorize</b> 按钮，粘贴 {@code JwtTokenProvider.issue} 生成的
 * JWT（不含 "Bearer " 前缀），后续请求即自动带 {@code Authorization: Bearer <token>} 头，
 * 与 {@code JwtAuthInterceptor} 的取值方式一致。安全方案只是文档层声明，是否真正校验由
 * {@code security.jwt.enabled} 决定（关闭时带不带 token 都放行）。
 */
@Configuration
public class OpenApiConfig {

    /** 安全方案名：Authorize 弹窗与该全局要求共用此 key */
    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI web3OpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("web3-backend 链上查询 API")
                        .description("基于 Web3j + Infura 的链上数据查询；开启 JWT 后可点 Authorize 填 Token"))
                // http + bearer + bearerFormat=JWT：Authorize 输入框只填裸 token
                .components(new Components().addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                // 全局默认要求：所有接口都带上 bearerAuth（拦截器再按 scope 细判）
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
