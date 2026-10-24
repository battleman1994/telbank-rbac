package com.telbank.rbac.security;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis Nonce 存储（SETNX + TTL）。Redis 不可用时降级为本地 Caffeine，保证开放接口可用性。
 */
public class RedisNonceStore implements NonceStore {

    private static final Logger log = LoggerFactory.getLogger(RedisNonceStore.class);
    private static final String KEY_PREFIX = "rbac:nonce:";

    private final StringRedisTemplate stringRedisTemplate;
    private final NonceStore fallback;

    public RedisNonceStore(StringRedisTemplate stringRedisTemplate, NonceStore fallback) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.fallback = fallback;
    }

    @Override
    public boolean storeIfAbsent(String key, Duration ttl) {
        try {
            Boolean stored = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + key, "1", ttl);
            return Boolean.TRUE.equals(stored);
        } catch (RuntimeException ex) {
            log.warn("Redis nonce store unavailable, falling back to local cache: {}", ex.getMessage());
            return fallback.storeIfAbsent(key, ttl);
        }
    }
}
