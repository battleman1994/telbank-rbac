package com.telbank.rbac.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.telbank.rbac.security.CaffeineNonceStore;
import com.telbank.rbac.security.NonceStore;
import com.telbank.rbac.security.RedisNonceStore;
import com.telbank.rbac.service.notify.ChangeEventPublisher;
import com.telbank.rbac.service.notify.LocalChangeEventPublisher;
import com.telbank.rbac.service.notify.RedisChangeEventPublisher;

/**
 * Redis 配置：String RedisTemplate + 变更发布器 + Nonce 防重放存储。
 *
 * <p>{@code rbac.redis.enabled=false} 时降级为本地实现：nonce 用 Caffeine，
 * 变更发布只打 warn 日志（不阻塞主流程），契约 §8.1 / §6。</p>
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> rbacRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @ConditionalOnProperty(name = "rbac.redis.enabled", havingValue = "true", matchIfMissing = true)
    public ChangeEventPublisher redisChangeEventPublisher(StringRedisTemplate stringRedisTemplate) {
        return new RedisChangeEventPublisher(stringRedisTemplate);
    }

    @Bean
    @ConditionalOnProperty(name = "rbac.redis.enabled", havingValue = "false")
    public ChangeEventPublisher localChangeEventPublisher() {
        return new LocalChangeEventPublisher();
    }

    @Bean
    @ConditionalOnProperty(name = "rbac.redis.enabled", havingValue = "true", matchIfMissing = true)
    public NonceStore redisNonceStore(StringRedisTemplate stringRedisTemplate) {
        return new RedisNonceStore(stringRedisTemplate, new CaffeineNonceStore());
    }

    @Bean
    @ConditionalOnProperty(name = "rbac.redis.enabled", havingValue = "false")
    public NonceStore caffeineNonceStore() {
        return new CaffeineNonceStore();
    }
}
