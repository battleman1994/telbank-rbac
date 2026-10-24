package com.telbank.rbac.sdk.cache;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.telbank.rbac.sdk.client.RbacOpenApiClient;
import com.telbank.rbac.sdk.client.RbacRemoteException;
import com.telbank.rbac.sdk.client.UserPermissionEntry;
import com.telbank.rbac.sdk.client.UserPermissionResponse;
import com.telbank.rbac.sdk.util.RbacPermissionMatcher;

/**
 * 用户权限集本地缓存（需求 §6.9，契约 §7.4）。
 *
 * <p>数据结构：{@code externalUserId -> systemCode -> code -> {validFrom, validUntil}}。</p>
 *
 * <p>关键语义：</p>
 * <ul>
 *   <li><b>不设 TTL</b>：权限一旦授予，{@code validUntil} 不可修改；缓存只由权限变更通知驱动失效，
 *       失效采用「标记 stale + 下次请求尝试回源」，回源失败时继续用旧缓存 + 时钟偏移判定
 *       （即契约 §7.2 第 5 条的「有旧缓存」分支）。</li>
 *   <li><b>时钟偏移</b>：每次回源用响应里的 {@code serverTime} 重算
 *       {@code offsetMillis = serverTime - System.currentTimeMillis()}；时效判定用
 *       {@code 本地时间 + offsetMillis} 与 validFrom/validUntil 比较。</li>
 *   <li><b>通配权限（A 方案）</b>：权限中心只会下发精确码、{@code *}（任意权限）或 {@code 前缀:*}
 *       （如 {@code order:*}，匹配 {@code order:delete} 等）；所有权限判定统一走
 *       {@link RbacPermissionMatcher}。有效权限集包含 {@code *} 时对外返回 {@code ["*"]}。</li>
 * </ul>
 */
public class RbacUserPermissionCache {

    private static final Logger log = LoggerFactory.getLogger(RbacUserPermissionCache.class);

    /** 平台超管通配权限码（契约 §4.2）。 */
    public static final String PLATFORM_ADMIN_CODE = "*";

    /** 回源失败后短时间内的重试退避，避免权限中心故障时每个请求都等超时。 */
    private static final long RELOAD_BACKOFF_MILLIS = 2000L;

    private final RbacOpenApiClient remoteClient;

    private final ConcurrentMap<String, CachedUserPermissions> cache =
            new ConcurrentHashMap<String, CachedUserPermissions>();

    private final ConcurrentMap<String, Object> loadLocks = new ConcurrentHashMap<String, Object>();

    private volatile long clockOffsetMillis;

    public RbacUserPermissionCache(RbacOpenApiClient remoteClient) {
        if (remoteClient == null) {
            throw new IllegalArgumentException("remoteClient 不能为空");
        }
        this.remoteClient = remoteClient;
    }

    /**
     * 权限判定（拦截器主路径）。
     *
     * @return ALLOW 有权限且时效有效；DENY 无权限/时效已过/参数非法；UNAVAILABLE 回源失败且无旧缓存
     */
    public Decision check(String externalUserId, String systemCode, String code) {
        if (isBlank(externalUserId) || isBlank(systemCode) || isBlank(code)) {
            return Decision.DENY;
        }
        CachedUserPermissions entry = obtain(externalUserId, systemCode);
        if (entry == null) {
            return Decision.UNAVAILABLE;
        }
        return isPermissionValid(entry, code) ? Decision.ALLOW : Decision.DENY;
    }

    /**
     * 获取当前有效权限码集合；包含全量通配 {@code *} 时返回 {@code ["*"]}。
     *
     * <p>其余情况原样返回有效权限码（可能包含 {@code 前缀:*} 通配条目，由前端 {@code hasPerm} 按同一
     * 口径匹配）。缓存未命中/stale 时回源（同时完成预热）。回源失败且有旧缓存时返回旧缓存中当前有效的
     * 权限码；无任何缓存时抛出 {@link RbacRemoteException}。</p>
     */
    public Set<String> getValidPermissions(String externalUserId, String systemCode) {
        if (isBlank(externalUserId) || isBlank(systemCode)) {
            throw new IllegalArgumentException("externalUserId/systemCode 不能为空");
        }
        CachedUserPermissions entry = obtain(externalUserId, systemCode);
        if (entry == null) {
            throw new RbacRemoteException("用户权限回源失败且无旧缓存: externalUserId=" + externalUserId
                    + ", systemCode=" + systemCode);
        }
        long now = nowWithOffset();
        if (isPermissionValid(entry, PLATFORM_ADMIN_CODE, now)) {
            return Collections.singleton(PLATFORM_ADMIN_CODE);
        }
        Set<String> result = new LinkedHashSet<String>();
        for (CachedPermission permission : entry.permissions.values()) {
            if (isWithinValidityWindow(permission.validFromMillis, permission.validUntilMillis, now)) {
                result.add(permission.code);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * 当前系统视角下是否拥有全量通配权限 {@code *}。
     *
     * <p>A 方案下平台超管与系统 admin 均只授一条 {@code *}，openapi 均下发 {@code *}；SDK 无法仅凭
     * 权限集区分二者，因此本方法语义为「在当前系统内拥有全部权限」。前缀通配（{@code order:*}）
     * 不算平台超管。</p>
     */
    public boolean isPlatformAdmin(String externalUserId, String systemCode) {
        return check(externalUserId, systemCode, PLATFORM_ADMIN_CODE) == Decision.ALLOW;
    }

    public boolean isLoaded(String externalUserId, String systemCode) {
        return cache.containsKey(key(externalUserId, systemCode));
    }

    /**
     * 失效全部用户缓存（PERMISSION_CHANGED）。保留旧数据用于回源失败时降级。
     */
    public void invalidateAll() {
        int count = 0;
        for (CachedUserPermissions entry : cache.values()) {
            entry.stale = true;
            count++;
        }
        log.info("RBAC 权限变更通知：已失效全部用户权限缓存, users={}", count);
    }

    /**
     * 失效单个用户（所有系统）的缓存。
     */
    public void invalidateUser(String externalUserId) {
        if (isBlank(externalUserId)) {
            return;
        }
        String prefix = externalUserId + '\u0000';
        int count = 0;
        for (Map.Entry<String, CachedUserPermissions> entry : cache.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                entry.getValue().stale = true;
                count++;
            }
        }
        log.info("RBAC 权限变更通知：已失效用户权限缓存, externalUserId={}, systems={}", externalUserId, count);
    }

    /**
     * 失效指定用户+系统的缓存。
     */
    public void invalidate(String externalUserId, String systemCode) {
        CachedUserPermissions entry = cache.get(key(externalUserId, systemCode));
        if (entry != null) {
            entry.stale = true;
            log.info("RBAC 权限变更通知：已失效用户权限缓存, externalUserId={}, systemCode={}",
                    externalUserId, systemCode);
        }
    }

    public long getClockOffsetMillis() {
        return clockOffsetMillis;
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
        loadLocks.clear();
        clockOffsetMillis = 0L;
    }

    /**
     * 时效窗口判定（公开静态方法便于单测；实际判定时间 = 本地时间 + 时钟偏移）。
     *
     * @param validFromMillis  null 表示立即生效
     * @param validUntilMillis null 表示永久有效；有效期右开区间（valid_until &gt; now）
     */
    public static boolean isWithinValidityWindow(Long validFromMillis, Long validUntilMillis, long nowMillis) {
        if (validFromMillis != null && nowMillis < validFromMillis.longValue()) {
            return false;
        }
        if (validUntilMillis != null && nowMillis >= validUntilMillis.longValue()) {
            return false;
        }
        return true;
    }

    private boolean isPermissionValid(CachedUserPermissions entry, String code) {
        return isPermissionValid(entry, code, nowWithOffset());
    }

    private boolean isPermissionValid(CachedUserPermissions entry, String requiredCode, long nowMillis) {
        if (requiredCode == null || requiredCode.trim().isEmpty()) {
            return false;
        }
        // 1) 精确码快速路径
        CachedPermission exact = entry.permissions.get(requiredCode);
        if (exact != null
                && isWithinValidityWindow(exact.validFromMillis, exact.validUntilMillis, nowMillis)) {
            return true;
        }
        // 2) 通配匹配（* / 前缀:*），只对当前时效有效的授权码生效
        for (CachedPermission granted : entry.permissions.values()) {
            if (!isWithinValidityWindow(granted.validFromMillis, granted.validUntilMillis, nowMillis)) {
                continue;
            }
            if (RbacPermissionMatcher.matches(granted.code, requiredCode)) {
                return true;
            }
        }
        return false;
    }

    private long nowWithOffset() {
        return System.currentTimeMillis() + clockOffsetMillis;
    }

    /**
     * 缓存命中直接返回；未命中/stale 时回源。回源失败且有旧缓存返回旧缓存，否则返回 null。
     */
    private CachedUserPermissions obtain(String externalUserId, String systemCode) {
        String key = key(externalUserId, systemCode);
        CachedUserPermissions entry = cache.get(key);
        if (entry != null && !entry.stale) {
            return entry;
        }
        Object lock = lockFor(key);
        synchronized (lock) {
            CachedUserPermissions current = cache.get(key);
            if (current != null && !current.stale) {
                return current;
            }
            CachedUserPermissions old = current;
            long now = System.currentTimeMillis();
            if (old != null && now - old.lastReloadAttemptMillis < RELOAD_BACKOFF_MILLIS) {
                // 回源刚失败过，短时间内直接用旧缓存，避免拖慢业务请求
                return old;
            }
            if (old != null) {
                old.lastReloadAttemptMillis = now;
            }
            try {
                CachedUserPermissions fresh = load(externalUserId, systemCode);
                cache.put(key, fresh);
                return fresh;
            } catch (RuntimeException e) {
                log.warn("回源用户权限失败, externalUserId={}, systemCode={}: {}",
                        externalUserId, systemCode, e.toString());
                if (old != null) {
                    old.stale = true;
                    return old;
                }
                return null;
            }
        }
    }

    private Object lockFor(String key) {
        Object lock = loadLocks.get(key);
        if (lock != null) {
            return lock;
        }
        Object candidate = new Object();
        Object existing = loadLocks.putIfAbsent(key, candidate);
        return existing == null ? candidate : existing;
    }

    private CachedUserPermissions load(String externalUserId, String systemCode) {
        UserPermissionResponse response = remoteClient.fetchUserPermissions(externalUserId);
        if (response == null) {
            throw new RbacRemoteException("权限中心返回空用户权限集");
        }
        String serverTime = response.getServerTime();
        if (isBlank(serverTime)) {
            log.warn("回源响应缺少 serverTime，沿用当前时钟偏移 offsetMillis={}", clockOffsetMillis);
        } else {
            Long serverTimeMillis = parseInstantMillis(serverTime);
            if (serverTimeMillis == null) {
                log.warn("回源响应 serverTime 格式非法({})，沿用当前时钟偏移 offsetMillis={}",
                        serverTime, clockOffsetMillis);
            } else {
                long offset = serverTimeMillis.longValue() - System.currentTimeMillis();
                this.clockOffsetMillis = offset;
                log.debug("用户权限回源完成, externalUserId={}, systemCode={}, 时钟偏移={}ms",
                        externalUserId, systemCode, offset);
            }
        }
        Map<String, CachedPermission> permissions = new LinkedHashMap<String, CachedPermission>();
        if (response.getPermissions() != null) {
            for (UserPermissionEntry entry : response.getPermissions()) {
                if (entry == null) {
                    continue;
                }
                String code = entry.getCode() == null ? null : entry.getCode().trim();
                if (code == null || code.isEmpty()) {
                    continue;
                }
                Long validFrom = parseRequiredInstant(entry.getValidFrom(), "validFrom", code);
                Long validUntil = parseRequiredInstant(entry.getValidUntil(), "validUntil", code);
                permissions.put(code, new CachedPermission(code, validFrom, validUntil));
            }
        }
        return new CachedUserPermissions(Collections.unmodifiableMap(permissions));
    }

    private static Long parseRequiredInstant(String value, String fieldName, String code) {
        if (isBlank(value)) {
            return null;
        }
        Long millis = parseInstantMillis(value);
        if (millis == null) {
            throw new RbacRemoteException("用户权限码[" + code + "]的 " + fieldName
                    + " 时间格式非法: " + value);
        }
        return millis;
    }

    /**
     * 解析 ISO-8601 时间（如 {@code 2026-10-31T23:59:59Z}）；null/blank 返回 null，格式非法返回 null。
     */
    public static Long parseInstantMillis(String value) {
        if (isBlank(value)) {
            return null;
        }
        String text = value.trim();
        try {
            return Long.valueOf(Instant.parse(text).toEpochMilli());
        } catch (DateTimeParseException ignored) {
            // 兼容带偏移量的写法，如 2026-10-31T23:59:59+08:00
        }
        try {
            return Long.valueOf(OffsetDateTime.parse(text).toInstant().toEpochMilli());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String key(String externalUserId, String systemCode) {
        return externalUserId + '\u0000' + systemCode;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 权限判定结果。
     */
    public enum Decision {
        /** 有权限且时效有效。 */
        ALLOW,
        /** 无该权限码或时效已过。 */
        DENY,
        /** 回源失败且没有旧缓存，调用方按 unmatched-strategy 处理。 */
        UNAVAILABLE
    }

    private static final class CachedPermission {

        private final String code;

        private final Long validFromMillis;

        private final Long validUntilMillis;

        CachedPermission(String code, Long validFromMillis, Long validUntilMillis) {
            this.code = code;
            this.validFromMillis = validFromMillis;
            this.validUntilMillis = validUntilMillis;
        }
    }

    private static final class CachedUserPermissions {

        private final Map<String, CachedPermission> permissions;

        private volatile boolean stale;

        private volatile long lastReloadAttemptMillis;

        CachedUserPermissions(Map<String, CachedPermission> permissions) {
            this.permissions = permissions;
        }
    }
}
