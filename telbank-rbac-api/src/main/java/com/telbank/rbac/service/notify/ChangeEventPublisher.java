package com.telbank.rbac.service.notify;

/**
 * 权限变更事件发布器抽象：Redis 可用时发布 Pub/Sub；不可用/禁用时降级为本地日志（no-op）。
 */
public interface ChangeEventPublisher {

    /**
     * 发布变更消息。
     *
     * @param channel 频道，如 {@code rbac:change:region_a:crm}
     * @param payload JSON 消息体
     */
    void publish(String channel, String payload);
}
