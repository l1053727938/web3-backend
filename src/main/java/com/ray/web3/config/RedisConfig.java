package com.ray.web3.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * RedisTemplate 序列化配置。
 * Spring Boot 4（Spring Data Redis 4）下基于 Jackson 2 的 GenericJackson2JsonRedisSerializer
 * 已标记 @Deprecated(forRemoval = true)，改用 Jackson 3（tools.jackson）版本。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Jackson 3 的 ObjectMapper 不可变，统一通过 JsonMapper.builder() 配置；
        // java.time 支持已内置（无需注册 JavaTimeModule），默认即 ISO-8601，此处显式声明防配置漂移。
        JsonMapper objectMapper = JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .changeDefaultVisibility(visibility -> visibility
                        .withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY))
                .build();

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJacksonJsonRedisSerializer valueSerializer = new GenericJacksonJsonRedisSerializer(objectMapper);

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
