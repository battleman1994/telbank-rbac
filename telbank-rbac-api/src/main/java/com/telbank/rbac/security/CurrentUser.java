package com.telbank.rbac.security;

/**
 * 当前认证用户上下文。
 *
 * @param externalUserId 外部用户 ID（JWT sub）
 * @param platformAdmin  是否平台超管（平台系统内有效通配权限 {@code *}，数据驱动）
 */
public record CurrentUser(String externalUserId, boolean platformAdmin) {
}
