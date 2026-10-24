package com.telbank.rbac.security;

/**
 * 管理端当前系统上下文（系统隔离边界）。
 *
 * @param systemId   系统 ID
 * @param systemCode 系统编码（X-System-Code）
 */
public record ManagementContext(Long systemId, String systemCode) {
}
