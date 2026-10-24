package com.telbank.rbac.security;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 本地 Caffeine Nonce 存储（{@code rbac.redis.enabled=false} 或 Redis 暂时不可用时使用）。
 */
public class CaffeineNonceStore implements NonceStore {

    private final Cache<String, Boolean> cache;

    public CaffeineNonceStore() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(200_000L)
                .build();
    }

    @Override
    public boolean storeIfAbsent(String key, Duration ttl) {
        return cache.asMap().putIfAbsent(key, Boolean.TRUE) == null;
    }
}
