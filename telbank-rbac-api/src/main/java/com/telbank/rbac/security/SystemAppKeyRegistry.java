package com.telbank.rbac.security;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.telbank.rbac.common.RbacConstants;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysSystemMapper;

/**
 * 开放接口 AppKey 注册表：优先从数据库 {@code sys_system.app_key/app_secret} 解析调用方
 * （系统接入时分配），YAML {@code rbac.clients} 作为历史 demo 的 fallback（见 AppKeyAuthFilter）。
 */
@Component
public class SystemAppKeyRegistry {

    private final SysSystemMapper systemRepository;

    public SystemAppKeyRegistry(SysSystemMapper systemRepository) {
        this.systemRepository = systemRepository;
    }

    /**
     * 按 appKey 查询启用中的业务系统凭据；platform 系统不接入开放接口（app_key 为 null）。
     */
    public Optional<AppKeyCredential> resolve(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            return Optional.empty();
        }
        SysSystem system = systemRepository.selectOne(Wrappers.<SysSystem>lambdaQuery()
                .eq(SysSystem::getAppKey, appKey.trim())
                .last("LIMIT 1"));
        if (system == null) {
            return Optional.empty();
        }
        return Optional.of(new AppKeyCredential(system.getAppKey(), system.getCode(),
                system.getAppSecret(),
                Integer.valueOf(RbacConstants.STATUS_ENABLED).equals(system.getStatus())));
    }

    /**
     * 数据库解析出的开放接口凭据。
     *
     * @param enabled 系统是否启用；DB 已有该 appKey 时（即使停用）过滤器不再回退 YAML 配置
     */
    public record AppKeyCredential(String appKey, String systemCode, String appSecret,
                                   boolean enabled) {
    }
}
