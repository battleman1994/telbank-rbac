package com.telbank.rbac.service.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地降级发布器：{@code rbac.redis.enabled=false}（联调/测试）或 Redis 发布失败时使用。
 *
 * <p>变更通知是「尽力而为」：发布失败只记 warn，不影响主流程（SDK 有短轮询兜底，契约 §6）。</p>
 */
public class LocalChangeEventPublisher implements ChangeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalChangeEventPublisher.class);

    @Override
    public void publish(String channel, String payload) {
        log.warn("redis change publishing disabled (rbac.redis.enabled=false), event dropped: channel={} payload={}",
                channel, payload);
    }
}
