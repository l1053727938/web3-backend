package com.ray.web3.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ray.web3.model.vo.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 拦截器层直接写响应体的小工具：鉴权/限流失败发生在 Controller 之前，
 * 无法走 GlobalExceptionHandler，故在此按统一 ApiResponse 结构输出 JSON
 */
@Slf4j
public final class HttpResponseUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpResponseUtil() {
    }

    /** 以 status 状态码写出 {code, message, data:null} 统一结构 */
    public static void writeJson(HttpServletResponse response, int status, int code, String message) {
        try {
            response.setStatus(status);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(MAPPER.writeValueAsString(ApiResponse.fail(code, message)));
        } catch (IOException e) {
            log.error("写拦截器响应失败", e);
        }
    }
}
