package com.telbank.rbac.security;

import java.time.Duration;

/**
 * Nonce 防重放存储（契约 §4.1：nonce 5 分钟内不可重复）。
 *
 * <p>Redis 可用时用 Redis（SETNX + TTL）实现；Redis 禁用或不可用时降级为本地 Caffeine 实现。</p>
 */
public interface NonceStore {

    /**
     * 尝试登记 nonce。
     *
     * @param key appKey + ":" + nonce
     * @param ttl 有效期
     * @return true 表示首次出现（登记成功）；false 表示重放
     */
    boolean storeIfAbsent(String key, Duration ttl);
}
