package com.telbank.rbac.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.config.RbacProperties;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.repository.SysSystemRepository;
import com.telbank.rbac.service.cache.LocalCacheManager;
import com.telbank.rbac.service.notify.ChangeEventPublisher;

import tools.jackson.databind.json.JsonMapper;

/**
 * 权限变更通知（契约 §6）：按 {@code sys_system.region} 路由到
 * {@code rbac:change:{region}:{systemCode}} 频道。
 *
 * <p>写操作在事务提交后发布（避免通知先于数据落库）；发布失败只记 warn，不影响主流程。</p>
 */
@Service
public class ChangeNotifyService {

    private static final Logger log = LoggerFactory.getLogger(ChangeNotifyService.class);

    private final SysSystemRepository systemRepository;
    private final RbacProperties properties;
    private final ChangeEventPublisher changeEventPublisher;
    private final LocalCacheManager localCacheManager;
    private final JsonMapper jsonMapper;

    public ChangeNotifyService(SysSystemRepository systemRepository,
                               RbacProperties properties,
                               ChangeEventPublisher changeEventPublisher,
                               LocalCacheManager localCacheManager,
                               JsonMapper jsonMapper) {
        this.systemRepository = systemRepository;
        this.properties = properties;
        this.changeEventPublisher = changeEventPublisher;
        this.localCacheManager = localCacheManager;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 事务提交后发布；无事务时立即发布。
     */
    public void publishAfterCommit(Long systemId, String changeType) {
        if (systemId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishNow(systemId, changeType);
                }
            });
        } else {
            publishNow(systemId, changeType);
        }
    }

    /**
     * 立即发布（含本地缓存失效）。
     */
    public void publishNow(Long systemId, String changeType) {
        if (RbacConstants.CHANGE_TYPE_PERMISSION.equals(changeType)) {
            localCacheManager.invalidatePermissions(systemId);
        } else if (RbacConstants.CHANGE_TYPE_PAGE_ACTION.equals(changeType)) {
            localCacheManager.invalidatePageActions(systemId);
        }
        SysSystem system = systemRepository.findById(systemId).orElse(null);
        if (system == null) {
            log.warn("skip rbac change event: system not found, systemId={}", systemId);
            return;
        }
        String channel = channel(system);
        String payload = payload(system.getCode(), changeType);
        try {
            changeEventPublisher.publish(channel, payload);
        } catch (RuntimeException ex) {
            log.warn("failed to publish rbac change event: channel={} payload={} error={}",
                    channel, payload, ex.getMessage());
        }
    }

    private String channel(SysSystem system) {
        RbacProperties.Region region = properties.getRegions().get(system.getRegion());
        String prefix = region != null && region.getChannelPrefix() != null
                && !region.getChannelPrefix().isBlank()
                ? region.getChannelPrefix()
                : "rbac:change:" + system.getRegion() + ":";
        return prefix + system.getCode();
    }

    private String payload(String systemCode, String changeType) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("systemCode", systemCode);
        message.put("type", changeType);
        message.put("timestamp", Instant.now().toString());
        return jsonMapper.writeValueAsString(message);
    }
}
