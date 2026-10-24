package com.telbank.rbac.service.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis Pub/Sub 变更发布器（契约 §6）。
 */
public class RedisChangeEventPublisher implements ChangeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisChangeEventPublisher.class);

    private final StringRedisTemplate stringRedisTemplate;

    public RedisChangeEventPublisher(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void publish(String channel, String payload) {
        Long receivers = stringRedisTemplate.convertAndSend(channel, payload);
        log.info("published rbac change event: channel={} receivers={} payload={}", channel, receivers, payload);
    }
}
