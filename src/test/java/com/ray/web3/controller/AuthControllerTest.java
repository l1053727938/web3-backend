package com.ray.web3.controller;

import com.ray.web3.config.AuthProperties;
import com.ray.web3.model.vo.ApiResponse;
import com.ray.web3.model.vo.TokenVO;
import com.ray.web3.util.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Token 签发接口离线单测（第 2 周）：覆盖三条分支——未配口令 503、口令不符 401、签发成功且 token 可被同密钥校验
 */
class AuthControllerTest {

    private static final String SECRET =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    /** 用 Mockito 替身：ObjectProvider 直接返回真实 provider，避免引入 Spring 上下文 */
    @SuppressWarnings("unchecked")
    private AuthController controller(String issuerAccessKey) {
        AuthProperties props = new AuthProperties();
        props.getJwt().setSecret(SECRET);
        props.getJwt().setTtlSeconds(600L);
        props.getJwt().setIssuerAccessKey(issuerAccessKey);

        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 600, 30);
        ObjectProvider<JwtTokenProvider> op = mock(ObjectProvider.class);
        when(op.getObject()).thenReturn(provider);
        return new AuthController(op, props);
    }

    @Test
    void 未配置口令时接口关闭返回503() {
        ResponseEntity<ApiResponse<TokenVO>> resp = controller(null).issueToken("whatever", "svc", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo(503);
    }

    @Test
    void 口令不符返回401() {
        ResponseEntity<ApiResponse<TokenVO>> resp = controller("right-key").issueToken("wrong-key", "svc", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo(401);
    }

    @Test
    void 口令正确时签发可用token且默认scope生效() {
        AuthController c = controller("right-key");
        ResponseEntity<ApiResponse<TokenVO>> resp = c.issueToken("right-key", "swagger-client", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        TokenVO vo = resp.getBody().data();
        assertThat(vo).isNotNull();
        // 未传 scope → 默认三个查询权限
        assertThat(vo.scope()).isEqualTo("chain:read log:query deposit:read");
        assertThat(vo.expiresInSecond()).isEqualTo(600L);

        // 签发的 token 能被同密钥校验，scope 与 sub 一致
        Claims claims = new JwtTokenProvider(SECRET, 600, 30).parse(vo.token());
        assertThat(claims.getSubject()).isEqualTo("swagger-client");
        assertThat(JwtTokenProvider.scopeOf(claims)).isEqualTo("chain:read log:query deposit:read");
    }
}
