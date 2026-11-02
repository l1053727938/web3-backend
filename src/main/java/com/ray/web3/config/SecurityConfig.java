package com.ray.web3.config;

import com.ray.web3.interceptor.JwtAuthInterceptor;
import com.ray.web3.interceptor.RateLimitInterceptor;
import com.ray.web3.ratelimit.DistributedRateLimitGuard;
import com.ray.web3.ratelimit.LocalRateLimitGuard;
import com.ray.web3.ratelimit.RateLimitGuard;
import com.ray.web3.util.JwtTokenProvider;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 对外 API 安全装配（第 2 周：鉴权打底 + 入口限流）
 * <p>
 * 两个能力均默认关闭（security.jwt.enabled / security.rate-limit.enabled），
 * 开启后才注册拦截器；拦截路径只覆盖 /api/chain/**，
 * /actuator/health（健康检查）、/swagger-ui/**、/v3/api-docs 不在拦截范围
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    /** 链上查询类接口统一前缀：JWT 拦截范围 */
    private static final String[] PROTECTED_PATHS = {"/api/chain/**"};

    /** 入口限流范围：eth_getLogs 类高配额消耗接口（分页拉取、解码拉取、补拉）+ 提币花钱类（防并发重复提交） */
    private static final String[] RATE_LIMITED_PATHS = {
            "/api/chain/logs/**", "/api/chain/block/**", "/api/chain/withdraw/**"};

    @Bean
    @ConditionalOnProperty(prefix = "security.jwt", name = "enabled", havingValue = "true")
    public JwtTokenProvider jwtTokenProvider(AuthProperties props) {
        AuthProperties.Jwt jwt = props.getJwt();
        if (jwt.getSecret() == null || jwt.getSecret().isBlank()) {
            throw new IllegalStateException(
                    "security.jwt.enabled=true 时必须配置 security.jwt.secret（Base64、原始字节≥32B）");
        }
        return new JwtTokenProvider(jwt.getSecret(), jwt.getTtlSeconds(), jwt.getClockSkewSeconds());
    }

    @Bean
    @ConditionalOnProperty(prefix = "security.jwt", name = "enabled", havingValue = "true")
    public JwtAuthInterceptor jwtAuthInterceptor(JwtTokenProvider provider) {
        return new JwtAuthInterceptor(provider);
    }

    @Bean
    @ConditionalOnProperty(prefix = "security.rate-limit", name = "enabled", havingValue = "true")
    public RateLimitGuard rateLimitGuard(AuthProperties props, ObjectProvider<RedissonClient> redisson) {
        AuthProperties.RateLimit cfg = props.getRateLimit();
        if (cfg.getMode() == AuthProperties.RateLimit.Mode.DISTRIBUTED) {
            RedissonClient client = redisson.getIfAvailable();
            if (client == null) {
                throw new IllegalStateException("security.rate-limit.mode=DISTRIBUTED 需要 Redis 就绪（RedissonClient 不可用）");
            }
            return new DistributedRateLimitGuard(cfg, client);
        }
        return new LocalRateLimitGuard(cfg);
    }

    @Bean
    @ConditionalOnProperty(prefix = "security.rate-limit", name = "enabled", havingValue = "true")
    public RateLimitInterceptor rateLimitInterceptor(RateLimitGuard guard, AuthProperties props) {
        return new RateLimitInterceptor(guard, props.getRateLimit());
    }

    /**
     * 拦截器注册：enabled=false 时对应拦截器完全不挂到 MVC 链上，
     * 行为与改造前保持一致（本地开发/已有调用方零影响）
     */
    @Configuration
    static class WebMvcInterceptorConfig implements WebMvcConfigurer {

        private final AuthProperties props;
        private final ObjectProvider<JwtAuthInterceptor> jwtInterceptor;
        private final ObjectProvider<RateLimitInterceptor> rateLimitInterceptor;

        WebMvcInterceptorConfig(AuthProperties props,
                                ObjectProvider<JwtAuthInterceptor> jwtInterceptor,
                                ObjectProvider<RateLimitInterceptor> rateLimitInterceptor) {
            this.props = props;
            this.jwtInterceptor = jwtInterceptor;
            this.rateLimitInterceptor = rateLimitInterceptor;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            // enabled=false 时 Bean 本身不创建（@ConditionalOnProperty），此处双保险跳过注册
            // 注册顺序：JWT 先于限流——限流分 key 依赖 JWT 写入的 sub 身份
            if (props.getJwt().isEnabled()) {
                registry.addInterceptor(jwtInterceptor.getObject()).addPathPatterns(PROTECTED_PATHS);
            }
            if (props.getRateLimit().isEnabled()) {
                registry.addInterceptor(rateLimitInterceptor.getObject()).addPathPatterns(RATE_LIMITED_PATHS);
            }
        }
    }
}
