package com.ray.web3.model.vo;

/**
 * 签发接口返回体：token 直接用于 Swagger Authorize 或 {@code Authorization: Bearer} 头
 *
 * @param token           裸 JWT（不含 "Bearer " 前缀）
 * @param scope           该 token 携带的权限清单
 * @param expiresInSecond 有效期（秒），到期需重新签发
 */
public record TokenVO(String token, String scope, long expiresInSecond) {
}
