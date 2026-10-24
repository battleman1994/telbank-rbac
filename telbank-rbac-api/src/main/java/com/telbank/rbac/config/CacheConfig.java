package com.telbank.rbac.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.telbank.rbac.service.cache.LocalCacheManager;

/**
 * Caffeine 一级缓存配置（契约 §9.5：管理端高频查询使用 Caffeine 本地缓存）。
 */
@Configuration
public class CacheConfig {

    /** 管理端查询缓存 TTL：5 分钟；写操作会主动失效，TTL 只是兜底。 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final long CACHE_MAX_SIZE = 2_000L;

    @Bean
    public LocalCacheManager localCacheManager() {
        return new LocalCacheManager(CACHE_TTL, CACHE_MAX_SIZE);
    }
}
